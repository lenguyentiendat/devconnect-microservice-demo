package com.devconnect.feed.cache;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-entry L1 expiry independently of Caffeine's size-based eviction.
 * Every Caffeine removal and every peer invalidation must remove its companion
 * entry so the metadata cannot outlive the local cache entry.
 */
public class L1ExpirationTracker {

    private final ConcurrentHashMap<String, Instant> expirations = new ConcurrentHashMap<>();

    public void record(String key, Instant expiration) {
        expirations.put(key, expiration);
    }

    public Instant get(String key) {
        return expirations.get(key);
    }

    public void remove(String key) {
        if (key != null) {
            expirations.remove(key);
        }
    }

    int size() {
        return expirations.size();
    }

    boolean contains(String key) {
        return expirations.containsKey(key);
    }
}
