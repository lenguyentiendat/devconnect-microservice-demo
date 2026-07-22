package com.devconnect.feed.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.io.IOException;
import java.util.Objects;

public class CacheInvalidationListener implements MessageListener {

    private static final String RECEIVED_COUNTER = "feed.cache.invalidation.received";
    private static final String MALFORMED_COUNTER = "feed.cache.invalidation.malformed";

    private final Cache<String, byte[]> localCache;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public CacheInvalidationListener(
            Cache<String, byte[]> localCache,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.localCache = Objects.requireNonNull(localCache, "localCache must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        onMessage(message == null ? null : message.getBody());
    }

    public void onMessage(byte[] message) {
        meterRegistry.counter(RECEIVED_COUNTER).increment();
        try {
            CacheInvalidation invalidation = objectMapper.readValue(message, CacheInvalidation.class);
            invalidateExact(invalidation.exactKey());
            invalidatePrefix(invalidation.prefix());
        } catch (IOException | RuntimeException exception) {
            meterRegistry.counter(MALFORMED_COUNTER).increment();
        }
    }

    private void invalidateExact(String key) {
        if (key != null && !key.isBlank()) {
            localCache.invalidate(key);
        }
    }

    private void invalidatePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        for (String key : localCache.asMap().keySet()) {
            if (key.startsWith(prefix)) {
                localCache.invalidate(key);
            }
        }
    }
}
