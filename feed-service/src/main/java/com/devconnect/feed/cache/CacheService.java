package com.devconnect.feed.cache;

import java.util.Optional;
import java.util.function.Supplier;

public interface CacheService {

    <T> Optional<T> getCacheByKey(String key, Class<T> valueType);

    <T> void addCacheByKey(String key, T value, CacheTtls ttls);

    boolean evictCacheByKey(String key);

    boolean evictCacheByExactKey(String key);

    long evictPrefixKey(String prefix);

    long evictLocalPrefix(String prefix);

    <T> T getOrLoad(String key, Class<T> valueType, CacheTtls ttls, Supplier<T> loader);
}
