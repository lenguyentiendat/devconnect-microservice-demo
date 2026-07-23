package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.Objects;

public class CacheKeyFactory {

    private static final String VERSION_PREFIX = ":feed:v1:";

    private final String prefix;

    public CacheKeyFactory(CacheProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.prefix = "devconnect:" + requireText(properties.environment(), "environment") + VERSION_PREFIX;
    }

    public String post(String postId) {
        return prefix + "post:" + requireText(postId, "postId");
    }

    public String feedRevision(String feedId) {
        return prefix + "revision:" + requireText(feedId, "feedId");
    }

    public String feedPage(String feedId, long revision, int size, LocalDateTime lastCreatedAt, String lastPostId) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if ((lastCreatedAt == null) != (lastPostId == null || lastPostId.isBlank())) {
            throw new IllegalArgumentException("lastCreatedAt and lastPostId must be provided together");
        }
        String cursor = lastCreatedAt == null ? null : lastCreatedAt + "|" + lastPostId;
        return feedPagePrefix(feedId) + "rev:" + revision + ":size:" + size + ":cursor:" + cursorHash(cursor);
    }

    public String feedPagePrefix(String feedId) {
        return prefix + "page:" + requireText(feedId, "feedId") + ":";
    }

    private static String cursorHash(String token) {
        if (token == null || token.isBlank()) {
            return "first";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
