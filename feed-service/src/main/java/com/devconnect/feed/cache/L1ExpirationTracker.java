package com.devconnect.feed.cache;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-entry L1 expiry independently of Caffeine's size-based eviction.
 * Every Caffeine removal and every peer invalidation must remove its companion
 * entry so the metadata cannot outlive the local cache entry.
 */
public class L1ExpirationTracker {

    private final ConcurrentHashMap<String, TrackedExpiration> expirations = new ConcurrentHashMap<>();

    public void record(String key, byte[] value, Instant expiration) {
        expirations.put(key, new TrackedExpiration(value, expiration));
    }

    public Instant get(String key, byte[] value) {
        TrackedExpiration trackedExpiration = expirations.get(key);
        return trackedExpiration != null && trackedExpiration.value() == value
                ? trackedExpiration.expiration()
                : null;
    }

    public void remove(String key, byte[] value) {
        if (key != null && value != null) {
            expirations.computeIfPresent(key, (ignored, trackedExpiration) ->
                    trackedExpiration.value() == value ? null : trackedExpiration
            );
        }
    }

    int size() {
        return expirations.size();
    }

    boolean contains(String key) {
        return expirations.containsKey(key);
    }

    private record TrackedExpiration(byte[] value, Instant expiration) {
    }
}
