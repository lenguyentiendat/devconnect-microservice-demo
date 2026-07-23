package com.devconnect.feed.cache;

import com.devconnect.feed.config.CacheProperties;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedRevisionServiceTests {

    private final RedisCacheStore redisCacheStore = mock(RedisCacheStore.class);
    private final CacheKeyFactory cacheKeyFactory = new CacheKeyFactory(cacheProperties());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final String revisionKey = "devconnect:local:feed:v1:revision:global";
    private final Logger logger = (Logger) LoggerFactory.getLogger(FeedRevisionService.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private FeedRevisionService revisions;

    @BeforeEach
    void setUp() {
        revisions = new FeedRevisionService(redisCacheStore, cacheKeyFactory, meterRegistry);
        logEvents.start();
        logger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logEvents);
        logEvents.stop();
    }

    @Test
    void currentInitializesMissingRevisionToOne() {
        when(redisCacheStore.get(revisionKey)).thenReturn(Optional.empty());
        when(redisCacheStore.putIfAbsent(revisionKey, "1".getBytes(StandardCharsets.UTF_8))).thenReturn(true);

        assertThat(revisions.current("global")).isEqualTo(1L);
    }

    @Test
    void advanceUsesRedisIncrement() {
        when(redisCacheStore.increment(revisionKey)).thenReturn(8L);

        assertThat(revisions.advance("global")).isEqualTo(8L);

        verify(redisCacheStore).increment(revisionKey);
    }

    @Test
    void redisFailuresReturnZeroAreCountedAndLogOperationAndExceptionTypeWithoutKeys() {
        when(redisCacheStore.get(revisionKey)).thenThrow(new RedisConnectionFailureException("down"));
        when(redisCacheStore.increment(revisionKey)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(revisions.current("global")).isZero();
        assertThat(revisions.advance("global")).isZero();
        assertThat(meterRegistry.counter("feed.cache.redis.errors").count()).isEqualTo(2.0);
        assertThat(logEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Feed revision current operation failed: RedisConnectionFailureException",
                        "Feed revision advance operation failed: RedisConnectionFailureException"
                )
                .noneMatch(message -> message.contains(revisionKey));
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
                "devconnect:cache:invalidation"
        );
    }
}
