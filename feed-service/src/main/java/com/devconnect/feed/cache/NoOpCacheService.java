package com.devconnect.feed.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class NoOpCacheService implements CacheService {

    @Override
    public <T> Optional<T> getCacheByKey(String key, Class<T> valueType) {
        return Optional.empty();
    }

    @Override
    public <T> void addCacheByKey(String key, T value, CacheTtls ttls) {
        // Cache operations are intentionally disabled.
    }

    @Override
    public boolean evictCacheByKey(String key) {
        return false;
    }

    @Override
    public boolean evictCacheByExactKey(String key) {
        return false;
    }

    @Override
    public long evictPrefixKey(String prefix) {
        return 0;
    }

    @Override
    public long evictLocalPrefix(String prefix) {
        return 0;
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> valueType, CacheTtls ttls, Supplier<T> loader) {
        return Objects.requireNonNull(loader, "loader must not be null").get();
    }
}
