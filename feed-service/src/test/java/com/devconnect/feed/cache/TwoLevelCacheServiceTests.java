package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.PostResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TwoLevelCacheServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Cache<String, byte[]> l1Cache = Caffeine.newBuilder().build();
    private final RedisCacheStore redisCacheStore = mock(RedisCacheStore.class);
    private final Supplier<PostResponse> loader = mock(Supplier.class);
    private final CacheTtls postTtls = new CacheTtls(Duration.ofSeconds(45), Duration.ofMinutes(5));
    private final String postKey = "devconnect:local:feed:v1:post:post-123";
    private final PostResponse post = new PostResponse(
            "post-123", "author-123", "cached post", LocalDateTime.of(2026, 7, 22, 10, 30)
    );

    private TwoLevelCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new TwoLevelCacheService(
                l1Cache,
                redisCacheStore,
                objectMapper,
                cacheProperties(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void l1HitDoesNotReadRedisOrCallLoader() {
        cacheService.addCacheByKey(postKey, post, postTtls);
        clearInvocations(redisCacheStore, loader);

        PostResponse result = cacheService.getOrLoad(postKey, PostResponse.class, postTtls, loader);

        assertThat(result).isEqualTo(post);
        verifyNoInteractions(redisCacheStore, loader);
    }

    @Test
    void redisHitBackfillsL1WithoutCallingLoader() throws Exception {
        when(redisCacheStore.get(postKey)).thenReturn(Optional.of(objectMapper.writeValueAsBytes(post)));

        assertThat(cacheService.getOrLoad(postKey, PostResponse.class, postTtls, loader)).isEqualTo(post);

        assertThat(l1Cache.getIfPresent(postKey)).isNotNull();
        verify(loader, never()).get();
    }

    @Test
    void redisFailureFallsBackToLoader() {
        when(redisCacheStore.get(postKey)).thenThrow(new RedisConnectionFailureException("down"));
        when(loader.get()).thenReturn(post);

        assertThat(cacheService.getOrLoad(postKey, PostResponse.class, postTtls, loader)).isEqualTo(post);

        verify(loader).get();
    }

    @Test
    void exactEvictionAndCompatibilityAliasRemoveOneKeyFromBothTiers() {
        cacheService.addCacheByKey(postKey, post, postTtls);

        assertThat(cacheService.evictCacheByKey(postKey)).isTrue();
        assertThat(cacheService.getCacheByKey(postKey, PostResponse.class)).isEmpty();

        verify(redisCacheStore).delete(postKey);
    }

    @Test
    void corruptL2ValueIsDeletedAndFallsBackToLoader() {
        when(redisCacheStore.get(postKey)).thenReturn(Optional.of(new byte[]{'{'}));
        when(loader.get()).thenReturn(post);

        assertThat(cacheService.getOrLoad(postKey, PostResponse.class, postTtls, loader)).isEqualTo(post);

        verify(redisCacheStore).delete(postKey);
        verify(loader).get();
    }

    private static CacheProperties cacheProperties() {
        return new CacheProperties(
                true,
                "local",
                100,
                Duration.ofSeconds(45),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                20,
                100,
                0,
                100,
                "devconnect:cache:invalidation",
                "page-token-secret"
        );
    }
}
