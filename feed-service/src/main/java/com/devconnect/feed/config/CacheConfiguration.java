package com.devconnect.feed.config;

import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.CacheInvalidationListener;
import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.cache.L1ExpirationTracker;
import com.devconnect.feed.cache.NoOpCacheService;
import com.devconnect.feed.cache.RedisCacheStore;
import com.devconnect.feed.cache.TwoLevelCacheService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    public L1ExpirationTracker l1ExpirationTracker() {
        return new L1ExpirationTracker();
    }

    @Bean
    public Cache<String, byte[]> caffeine(CacheProperties properties, L1ExpirationTracker l1ExpirationTracker) {
        return Caffeine.<String, byte[]>newBuilder()
                .maximumSize(properties.l1MaximumSize())
                .removalListener((String key, byte[] value, RemovalCause cause) -> l1ExpirationTracker.remove(key))
                .build();
    }

    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisCacheStore redisCacheStore(RedisTemplate<String, byte[]> redisTemplate, CacheProperties properties) {
        return new RedisCacheStore(redisTemplate, properties);
    }

    @Bean
    public CacheKeyFactory cacheKeyFactory(CacheProperties properties) {
        return new CacheKeyFactory(properties);
    }

    @Bean
    public FeedRevisionService feedRevisionService(
            RedisCacheStore redisCacheStore,
            CacheKeyFactory cacheKeyFactory,
            MeterRegistry meterRegistry
    ) {
        return new FeedRevisionService(redisCacheStore, cacheKeyFactory, meterRegistry);
    }

    @Bean
    public CacheInvalidationPublisher cacheInvalidationPublisher(
            RedisCacheStore redisCacheStore,
            ObjectMapper objectMapper,
            CacheProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new CacheInvalidationPublisher(redisCacheStore, objectMapper, properties, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheInvalidationListener cacheInvalidationListener(
            Cache<String, byte[]> caffeine,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            L1ExpirationTracker l1ExpirationTracker
    ) {
        return new CacheInvalidationListener(caffeine, objectMapper, meterRegistry, l1ExpirationTracker);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener cacheInvalidationListener,
            CacheProperties properties,
            MeterRegistry meterRegistry
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidationListener, new ChannelTopic(properties.invalidationChannel()));
        container.setRecoveryInterval(5_000L);
        container.setErrorHandler(exception -> {
            meterRegistry.counter("feed.cache.redis.errors").increment();
            LOGGER.warn("Cache invalidation listener operation failed: {}", exception.getClass().getSimpleName());
        });
        return container;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheService twoLevelCacheService(
            Cache<String, byte[]> caffeine,
            RedisCacheStore redisCacheStore,
            ObjectMapper objectMapper,
            CacheProperties properties,
            MeterRegistry meterRegistry,
            L1ExpirationTracker l1ExpirationTracker
    ) {
        return new TwoLevelCacheService(
                caffeine, redisCacheStore, objectMapper, properties, meterRegistry, l1ExpirationTracker
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "false")
    public CacheService noOpCacheService() {
        return new NoOpCacheService();
    }
}
