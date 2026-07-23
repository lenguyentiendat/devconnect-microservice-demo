package com.devconnect.feed.service;

import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.client.UserServiceAdapter;
import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceParallelValidationTests {

    private UserServiceAdapter userServiceAdapter;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;
    private ThreadPoolTaskExecutor executor;
    private CacheProperties cacheProperties;

    @BeforeEach
    void setUp() {
        userServiceAdapter = mock(UserServiceAdapter.class);
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("post-async-test-");
        executor.initialize();
        cacheProperties = cacheProperties();
        feedService = new FeedService(
                userServiceAdapter,
                publisher,
                postStore,
                executor,
                mock(CacheService.class),
                new CacheKeyFactory(cacheProperties),
                cacheProperties,
                mock(FeedRevisionService.class),
                mock(CacheInvalidationPublisher.class)
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void createPostSubmitsBothValidationsBeforeWaiting() {
        when(userServiceAdapter.getUserStatus("u001"))
                .thenReturn(new UserStatusResponse("u001", "ACTIVE"));
        CoordinatingExecutor coordinatingExecutor = new CoordinatingExecutor();
        feedService = new FeedService(
                userServiceAdapter,
                publisher,
                postStore,
                coordinatingExecutor,
                mock(CacheService.class),
                new CacheKeyFactory(cacheProperties),
                cacheProperties,
                mock(FeedRevisionService.class),
                mock(CacheInvalidationPublisher.class)
        );

        PostResponse post = feedService.createPost(
                new CreatePostRequest("u001", "Hello DevConnect")
        );

        assertEquals(2, coordinatingExecutor.submittedTasks.get());
        assertTrue(coordinatingExecutor.startedConcurrently.get());
        assertEquals("u001", post.authorId());
        verify(postStore).save(post);
        verify(publisher).publishPostCreated(any());
        verify(userServiceAdapter).getUserStatus("u001");
    }

    @Test
    void createPostRejectsProhibitedContentBeforeSaving() {
        when(userServiceAdapter.getUserStatus("u001"))
                .thenReturn(new UserStatusResponse("u001", "ACTIVE"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(
                        new CreatePostRequest("u001", "This is a SCAM")
                )
        );

        assertEquals("Post content contains prohibited words", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        verify(userServiceAdapter).getUserStatus("u001");
    }

    private static CacheProperties cacheProperties() {
        return new CacheProperties(
                true, "local", 100,
                Duration.ofSeconds(45), Duration.ofMinutes(5),
                Duration.ofSeconds(10), Duration.ofMinutes(1),
                20, 100, 0, 100,
                "devconnect:cache:invalidation"
        );
    }

    private static final class CoordinatingExecutor implements Executor {

        private final AtomicInteger submittedTasks = new AtomicInteger();
        private final CountDownLatch validationTasksStarted = new CountDownLatch(2);
        private final AtomicBoolean startedConcurrently = new AtomicBoolean();

        @Override
        public void execute(Runnable command) {
            int taskNumber = submittedTasks.incrementAndGet();
            Thread thread = new Thread(() -> {
                validationTasksStarted.countDown();
                try {
                    if (validationTasksStarted.await(2, TimeUnit.SECONDS)) {
                        startedConcurrently.set(true);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                command.run();
            }, "parallel-validation-" + taskNumber);
            thread.start();
        }
    }
}
