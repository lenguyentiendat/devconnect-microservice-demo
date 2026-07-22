package com.devconnect.feed.config;

import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.NoOpCacheService;
import com.devconnect.feed.cache.RedisCacheStore;
import com.devconnect.feed.cache.TwoLevelCacheService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

    @Bean
    public Cache<String, byte[]> caffeine(CacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.l1MaximumSize())
                .<String, byte[]>build();
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
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheService twoLevelCacheService(
            Cache<String, byte[]> caffeine,
            RedisCacheStore redisCacheStore,
            ObjectMapper objectMapper,
            CacheProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new TwoLevelCacheService(caffeine, redisCacheStore, objectMapper, properties, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "false")
    public CacheService noOpCacheService() {
        return new NoOpCacheService();
    }
}
