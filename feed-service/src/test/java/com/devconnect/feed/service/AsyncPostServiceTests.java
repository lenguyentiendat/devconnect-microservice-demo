package com.devconnect.feed.service;

import com.devconnect.feed.config.AsyncConfig;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig({AsyncConfig.class, AsyncPostService.class, AsyncPostServiceTests.TestConfig.class})
class AsyncPostServiceTests {

    @Autowired
    private AsyncPostService asyncPostService;

    @Autowired
    private FeedService feedService;

    @BeforeEach
    void resetMock() {
        reset(feedService);
    }

    @Test
    void createPostRunsOnDedicatedExecutor() {
        CreatePostRequest request = new CreatePostRequest("u001", "Async Java");
        PostResponse expected = new PostResponse(
                "post-1", "u001", "Async Java", LocalDateTime.now()
        );
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> workerThread = new AtomicReference<>();

        when(feedService.createPost(any())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            return expected;
        });

        PostResponse actual = asyncPostService.createPost(request).join();

        assertEquals(expected, actual);
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(workerThread.get().startsWith("post-async-"));
        verify(feedService).createPost(request);
    }

    @Test
    void createPostCompletesFutureExceptionallyWhenBusinessRuleFails() {
        CreatePostRequest request = new CreatePostRequest("u003", "Not allowed");
        when(feedService.createPost(request)).thenThrow(new BusinessException("Author is not active"));

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> asyncPostService.createPost(request).join()
        );

        assertInstanceOf(BusinessException.class, exception.getCause());
        assertEquals("Author is not active", exception.getCause().getMessage());
    }

    @Configuration
    static class TestConfig {

        @Bean
        FeedService feedService() {
            return mock(FeedService.class);
        }
    }
}
