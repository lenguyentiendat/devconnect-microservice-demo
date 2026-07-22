package com.devconnect.feed.cache;

import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Objects;

public class FeedRevisionService {

    private static final String REDIS_ERROR_COUNTER = "feed.cache.redis.errors";
    private static final byte[] INITIAL_REVISION = "1".getBytes(StandardCharsets.UTF_8);

    private final RedisCacheStore redisCacheStore;
    private final CacheKeyFactory cacheKeyFactory;
    private final MeterRegistry meterRegistry;

    public FeedRevisionService(
            RedisCacheStore redisCacheStore,
            CacheKeyFactory cacheKeyFactory,
            MeterRegistry meterRegistry
    ) {
        this.redisCacheStore = Objects.requireNonNull(redisCacheStore, "redisCacheStore must not be null");
        this.cacheKeyFactory = Objects.requireNonNull(cacheKeyFactory, "cacheKeyFactory must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public long current(String feedId) {
        String revisionKey = cacheKeyFactory.feedRevision(feedId);
        try {
            Optional<byte[]> storedRevision = redisCacheStore.get(revisionKey);
            if (storedRevision.isPresent()) {
                return parseRevision(storedRevision.get());
            }
            if (redisCacheStore.putIfAbsent(revisionKey, INITIAL_REVISION)) {
                return 1L;
            }
            return redisCacheStore.get(revisionKey).map(FeedRevisionService::parseRevision).orElse(0L);
        } catch (RuntimeException exception) {
            recordRedisError();
            return 0L;
        }
    }

    public long advance(String feedId) {
        try {
            return redisCacheStore.increment(cacheKeyFactory.feedRevision(feedId));
        } catch (RuntimeException exception) {
            recordRedisError();
            return 0L;
        }
    }

    private static long parseRevision(byte[] value) {
        long revision = Long.parseLong(new String(value, StandardCharsets.US_ASCII));
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return revision;
    }

    private void recordRedisError() {
        meterRegistry.counter(REDIS_ERROR_COUNTER).increment();
    }
}
