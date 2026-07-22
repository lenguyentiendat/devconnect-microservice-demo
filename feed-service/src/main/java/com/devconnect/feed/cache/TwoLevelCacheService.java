package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class TwoLevelCacheService implements CacheService {

    private static final String REDIS_ERROR_COUNTER = "feed.cache.redis.errors";
    private static final String SERIALIZATION_ERROR_COUNTER = "feed.cache.serialization.errors";

    private final Cache<String, byte[]> l1Cache;
    private final RedisCacheStore redisCacheStore;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int ttlJitterPercent;
    private final L1ExpirationTracker l1ExpirationTracker;

    public TwoLevelCacheService(
            Cache<String, byte[]> l1Cache,
            RedisCacheStore redisCacheStore,
            ObjectMapper objectMapper,
            CacheProperties properties,
            MeterRegistry meterRegistry,
            L1ExpirationTracker l1ExpirationTracker
    ) {
        this.l1Cache = Objects.requireNonNull(l1Cache, "l1Cache must not be null");
        this.redisCacheStore = Objects.requireNonNull(redisCacheStore, "redisCacheStore must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.l1ExpirationTracker = Objects.requireNonNull(l1ExpirationTracker, "l1ExpirationTracker must not be null");
        this.ttlJitterPercent = Objects.requireNonNull(properties, "properties must not be null").ttlJitterPercent();
    }

    @Override
    public <T> Optional<T> getCacheByKey(String key, Class<T> valueType) {
        validateReadArguments(key, valueType);

        Optional<T> l1Value = readL1(key, valueType);
        if (l1Value.isPresent()) {
            return l1Value;
        }
        return readL2(key, valueType).map(CachedValue::value);
    }

    @Override
    public <T> void addCacheByKey(String key, T value, CacheTtls ttls) {
        validateWriteArguments(key, value, ttls);
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(value);
            storeL1(key, serialized, ttls.l1());
            try {
                redisCacheStore.put(key, serialized, jitter(ttls.l2()));
            } catch (RuntimeException exception) {
                recordRedisError();
            }
        } catch (IOException exception) {
            recordSerializationError();
        }
    }

    @Override
    public boolean evictCacheByKey(String key) {
        return evictCacheByExactKey(key);
    }

    @Override
    public boolean evictCacheByExactKey(String key) {
        validateKey(key, "key");
        boolean removedFromL1 = l1Cache.asMap().remove(key) != null;
        l1ExpirationTracker.remove(key);
        try {
            return redisCacheStore.delete(key) || removedFromL1;
        } catch (RuntimeException exception) {
            recordRedisError();
            return removedFromL1;
        }
    }

    @Override
    public long evictPrefixKey(String prefix) {
        validateKey(prefix, "prefix");
        long removedFromL1 = removeL1Prefix(prefix);
        try {
            return removedFromL1 + redisCacheStore.scanDelete(prefix);
        } catch (RuntimeException exception) {
            recordRedisError();
            return removedFromL1;
        }
    }

    @Override
    public long evictLocalPrefix(String prefix) {
        validateKey(prefix, "prefix");
        return removeL1Prefix(prefix);
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> valueType, CacheTtls ttls, Supplier<T> loader) {
        validateReadArguments(key, valueType);
        Objects.requireNonNull(ttls, "ttls must not be null");
        Objects.requireNonNull(loader, "loader must not be null");

        Optional<T> l1Value = readL1(key, valueType);
        if (l1Value.isPresent()) {
            return l1Value.get();
        }

        Optional<CachedValue<T>> l2Value = readL2(key, valueType);
        if (l2Value.isPresent()) {
            CachedValue<T> cached = l2Value.get();
            storeL1(key, cached.serialized(), ttls.l1());
            return cached.value();
        }

        T loaded = loader.get();
        if (loaded != null) {
            addCacheByKey(key, loaded, ttls);
        }
        return loaded;
    }

    private <T> Optional<T> readL1(String key, Class<T> valueType) {
        byte[] serialized = l1Cache.getIfPresent(key);
        if (serialized == null) {
            l1ExpirationTracker.remove(key);
            return Optional.empty();
        }
        Instant expiration = l1ExpirationTracker.get(key);
        if (expiration != null && !expiration.isAfter(Instant.now())) {
            removeL1(key);
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(serialized.clone(), valueType));
        } catch (IOException exception) {
            removeL1(key);
            recordSerializationError();
            return Optional.empty();
        }
    }

    private <T> Optional<CachedValue<T>> readL2(String key, Class<T> valueType) {
        Optional<byte[]> serialized;
        try {
            serialized = redisCacheStore.get(key);
        } catch (RuntimeException exception) {
            recordRedisError();
            return Optional.empty();
        }
        if (serialized.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = serialized.get().clone();
            return Optional.of(new CachedValue<>(objectMapper.readValue(bytes, valueType), bytes));
        } catch (IOException exception) {
            recordSerializationError();
            try {
                redisCacheStore.delete(key);
            } catch (RuntimeException redisException) {
                recordRedisError();
            }
            return Optional.empty();
        }
    }

    private void storeL1(String key, byte[] serialized, Duration ttl) {
        l1ExpirationTracker.record(key, Instant.now().plus(jitter(ttl)));
        l1Cache.put(key, serialized.clone());
    }

    private long removeL1Prefix(String prefix) {
        long removed = 0;
        for (String key : l1Cache.asMap().keySet()) {
            if (key.startsWith(prefix) && l1Cache.asMap().remove(key) != null) {
                l1ExpirationTracker.remove(key);
                removed++;
            }
        }
        return removed;
    }

    private void removeL1(String key) {
        l1Cache.invalidate(key);
        l1ExpirationTracker.remove(key);
    }

    private Duration jitter(Duration ttl) {
        long ttlMillis = ttl.toMillis();
        int percentage = ttlJitterPercent == 0 ? 0 : ThreadLocalRandom.current().nextInt(ttlJitterPercent + 1);
        long jitteredMillis = ttlMillis - (ttlMillis * percentage / 100);
        return Duration.ofMillis(Math.max(Duration.ofSeconds(1).toMillis(), jitteredMillis));
    }

    private void validateReadArguments(String key, Class<?> valueType) {
        validateKey(key, "key");
        Objects.requireNonNull(valueType, "valueType must not be null");
    }

    private void validateWriteArguments(String key, Object value, CacheTtls ttls) {
        validateKey(key, "key");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(ttls, "ttls must not be null");
    }

    private static void validateKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private void recordRedisError() {
        meterRegistry.counter(REDIS_ERROR_COUNTER).increment();
    }

    private void recordSerializationError() {
        meterRegistry.counter(SERIALIZATION_ERROR_COUNTER).increment();
    }

    private record CachedValue<T>(T value, byte[] serialized) {
    }
}
