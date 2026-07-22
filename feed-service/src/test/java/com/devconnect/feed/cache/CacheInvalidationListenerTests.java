package com.devconnect.feed.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CacheInvalidationListenerTests {

    private final Cache<String, byte[]> localCache = Caffeine.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final L1ExpirationTracker expirationTracker = new L1ExpirationTracker();

    private CacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        listener = new CacheInvalidationListener(localCache, objectMapper, meterRegistry, expirationTracker);
    }

    @Test
    void listenerEvictsExactAndPagePrefixLocally() throws Exception {
        byte[] exactValue = new byte[] {1};
        byte[] pageValue = new byte[] {2};
        byte[] unrelatedValue = new byte[] {3};
        localCache.put("exact-post-key", exactValue);
        localCache.put("page-prefix:first", pageValue);
        localCache.put("unrelated-key", unrelatedValue);
        expirationTracker.record("exact-post-key", exactValue, Instant.now());
        expirationTracker.record("page-prefix:first", pageValue, Instant.now());
        expirationTracker.record("unrelated-key", unrelatedValue, Instant.now());

        listener.onMessage(json("exact-post-key", "page-prefix"));

        assertThat(localCache.getIfPresent("exact-post-key")).isNull();
        assertThat(localCache.getIfPresent("page-prefix:first")).isNull();
        assertThat(localCache.getIfPresent("unrelated-key")).isNotNull();
        assertThat(expirationTracker.contains("exact-post-key")).isFalse();
        assertThat(expirationTracker.contains("page-prefix:first")).isFalse();
        assertThat(expirationTracker.contains("unrelated-key")).isTrue();
        assertThat(meterRegistry.counter("feed.cache.invalidation.received").count()).isEqualTo(1.0);
    }

    @Test
    void malformedMessageIsIgnoredAndCounted() {
        assertThatCode(() -> listener.onMessage("not-json".getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter("feed.cache.invalidation.malformed").count()).isEqualTo(1.0);
    }

    private byte[] json(String exactKey, String prefix) throws Exception {
        return objectMapper.writeValueAsBytes(new CacheInvalidation(exactKey, prefix));
    }
}
