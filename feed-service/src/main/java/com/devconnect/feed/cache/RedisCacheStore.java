package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class RedisCacheStore {

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisCacheStore(RedisTemplate<String, byte[]> redisTemplate, CacheProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
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

}
