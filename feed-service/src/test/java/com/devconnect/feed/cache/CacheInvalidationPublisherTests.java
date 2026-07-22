package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CacheInvalidationPublisherTests {

    private final RedisCacheStore redisCacheStore = mock(RedisCacheStore.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CacheInvalidationPublisher(
                redisCacheStore,
                new ObjectMapper(),
                cacheProperties(),
                meterRegistry
        );
    }

    @Test
    void publishesJsonInvalidationMessageAndCountsIt() {
        publisher.publish(new CacheInvalidation("post-key", "page-prefix"));

        verify(redisCacheStore).publish(eq("devconnect:cache:invalidation"), any(byte[].class));
    }

    @Test
    void redisPublicationFailureDoesNotEscapeToCaller() {
        when(redisCacheStore.publish(eq("devconnect:cache:invalidation"), any(byte[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> publisher.publish(new CacheInvalidation("post-key", "page-prefix")))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter("feed.cache.redis.errors").count()).isEqualTo(1.0);
    }

    @Test
    void jsonSerializationFailureIsCountedSeparatelyFromRedisFailures() throws JsonProcessingException {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        publisher = new CacheInvalidationPublisher(
                redisCacheStore,
                failingObjectMapper,
                cacheProperties(),
                meterRegistry
        );
        when(failingObjectMapper.writeValueAsBytes(any()))
                .thenThrow(new JsonMappingException(null, "serialization failed"));

        assertThatCode(() -> publisher.publish(new CacheInvalidation("post-key", "page-prefix")))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter("feed.cache.serialization.errors").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("feed.cache.redis.errors").count()).isZero();
        verifyNoInteractions(redisCacheStore);
    }

    @Test
    void jsonConfigurationFailureIsCountedSeparatelyFromRedisFailures() throws JsonProcessingException {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        publisher = new CacheInvalidationPublisher(
                redisCacheStore,
                failingObjectMapper,
                cacheProperties(),
                meterRegistry
        );
        when(failingObjectMapper.writeValueAsBytes(any()))
                .thenThrow(new IllegalStateException("invalid mapper configuration"));

        assertThatCode(() -> publisher.publish(new CacheInvalidation("post-key", "page-prefix")))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter("feed.cache.serialization.errors").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("feed.cache.redis.errors").count()).isZero();
        verifyNoInteractions(redisCacheStore);
    }

    private static CacheProperties cacheProperties() {
        return new CacheProperties(
                true,
                "local",
                100,
                Duration.ofSeconds(45),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                20,
                100,
                0,
                100,
                "devconnect:cache:invalidation",
                "page-token-secret"
        );
    }
}
