package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class RedisCacheStore {

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final int scanBatchSize;

    public RedisCacheStore(RedisTemplate<String, byte[]> redisTemplate, CacheProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.scanBatchSize = Objects.requireNonNull(properties, "properties must not be null").scanBatchSize();
    }

    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key)).map(byte[]::clone);
    }

    public void put(String key, byte[] value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value.clone(), ttl);
    }

    public boolean putIfAbsent(String key, byte[] value) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value.clone()));
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public long scanDelete(String prefix) {
        Long deleted = redisTemplate.execute((RedisCallback<Long>) connection -> scanDelete(connection, prefix));
        return deleted == null ? 0 : deleted;
    }

    public long increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value == null) {
            throw new IllegalStateException("Redis increment returned null");
        }
        return value;
    }

    public long publish(String channel, byte[] message) {
        Long recipients = redisTemplate.convertAndSend(channel, message.clone());
        return recipients == null ? 0 : recipients;
    }

    private long scanDelete(RedisConnection connection, String prefix) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(scanBatchSize)
                .build();
        List<byte[]> batch = new ArrayList<>(scanBatchSize);
        long deleted = 0;

        try (Cursor<byte[]> cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == scanBatchSize) {
                    deleted += deleteBatch(connection, batch);
                }
            }
            if (!batch.isEmpty()) {
                deleted += deleteBatch(connection, batch);
            }
        }
        return deleted;
    }

    private static long deleteBatch(RedisConnection connection, List<byte[]> batch) {
        Long deleted = connection.del(batch.toArray(byte[][]::new));
        batch.clear();
        return deleted == null ? 0 : deleted;
    }
}
