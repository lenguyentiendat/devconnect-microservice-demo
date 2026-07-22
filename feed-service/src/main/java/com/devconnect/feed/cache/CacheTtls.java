package com.devconnect.feed.cache;

import java.time.Duration;
import java.util.Objects;

public record CacheTtls(Duration l1, Duration l2) {

    public CacheTtls {
        validate(l1, "l1");
        validate(l2, "l2");
    }

    private static void validate(Duration ttl, String name) {
        Objects.requireNonNull(ttl, name + " must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
