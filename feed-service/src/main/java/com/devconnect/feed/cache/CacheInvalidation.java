package com.devconnect.feed.cache;

public record CacheInvalidation(String exactKey, String prefix) {
}
