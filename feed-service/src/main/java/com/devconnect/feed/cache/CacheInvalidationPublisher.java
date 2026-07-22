package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class CacheInvalidationPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationPublisher.class);
    private static final String PUBLISHED_COUNTER = "feed.cache.invalidation.published";
    private static final String REDIS_ERROR_COUNTER = "feed.cache.redis.errors";
    private static final String SERIALIZATION_ERROR_COUNTER = "feed.cache.serialization.errors";

    private final RedisCacheStore redisCacheStore;
    private final ObjectMapper objectMapper;
    private final String invalidationChannel;
    private final MeterRegistry meterRegistry;

    public CacheInvalidationPublisher(
            RedisCacheStore redisCacheStore,
            ObjectMapper objectMapper,
            CacheProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.redisCacheStore = Objects.requireNonNull(redisCacheStore, "redisCacheStore must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.invalidationChannel = Objects.requireNonNull(properties, "properties must not be null").invalidationChannel();
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public void publish(CacheInvalidation invalidation) {
        Objects.requireNonNull(invalidation, "invalidation must not be null");
        byte[] message;
        try {
            message = objectMapper.writeValueAsBytes(invalidation);
        } catch (JsonProcessingException | RuntimeException exception) {
            meterRegistry.counter(SERIALIZATION_ERROR_COUNTER).increment();
            LOGGER.warn("Cache invalidation serialization operation failed: {}", exception.getClass().getSimpleName());
            return;
        }
        try {
            redisCacheStore.publish(invalidationChannel, message);
            meterRegistry.counter(PUBLISHED_COUNTER).increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(REDIS_ERROR_COUNTER).increment();
            LOGGER.warn("Cache invalidation publish operation failed: {}", exception.getClass().getSimpleName());
        }
    }
}
