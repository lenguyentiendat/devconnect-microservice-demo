package com.devconnect.feed.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.CacheTtls;
import com.devconnect.feed.dto.PostResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cassandra.schema-action=none",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers(disabledWithoutDocker = true)
class RedisCacheIntegrationTests {

    private static final CacheTtls TTL = new CacheTtls(Duration.ofSeconds(30), Duration.ofSeconds(30));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @MockitoBean
    private CqlSession cqlSession;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private CacheKeyFactory cacheKeys;

    @Autowired
    private RedisTemplate<String, byte[]> redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("app.cache.enabled", () -> true);
        registry.add("app.cache.page-token-secret", () -> "redis-integration-test-secret");
    }

    @Test
    void storesReadsAndEvictsAnExactCacheKeyInRedis() {
        String key = cacheKeys.post("integration-post");
        PostResponse post = post("integration-post");

        cacheService.addCacheByKey(key, post, TTL);

        assertThat(cacheService.getCacheByKey(key, PostResponse.class)).contains(post);
        assertThat(redisTemplate.hasKey(key)).isTrue();
        assertThat(cacheService.evictCacheByExactKey(key)).isTrue();
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void evictsOnlyKeysMatchingTheRequestedPrefix() {
        String pagePrefix = cacheKeys.feedPagePrefix("global");
        String firstPageKey = cacheKeys.feedPage("global", 1, 20, null);
        String nextPageKey = cacheKeys.feedPage("global", 2, 20, "next-page");
        String postKey = cacheKeys.post("unrelated-post");

        cacheService.addCacheByKey(firstPageKey, post("first"), TTL);
        cacheService.addCacheByKey(nextPageKey, post("next"), TTL);
        cacheService.addCacheByKey(postKey, post("unrelated-post"), TTL);

        assertThat(cacheService.evictPrefixKey(pagePrefix)).isEqualTo(4);
        assertThat(redisTemplate.hasKey(firstPageKey)).isFalse();
        assertThat(redisTemplate.hasKey(nextPageKey)).isFalse();
        assertThat(redisTemplate.hasKey(postKey)).isTrue();
    }

    private static PostResponse post(String postId) {
        return new PostResponse(postId, "author-1", "Redis integration post", LocalDateTime.of(2026, 7, 22, 10, 0));
    }
}
