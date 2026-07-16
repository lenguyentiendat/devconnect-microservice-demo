package com.devconnect.feed.service;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedServiceAsyncTests {

    private MockRestServiceServer server;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("post-async-test-");
        executor.initialize();
        feedService = new FeedService(
                builder.baseUrl("http://user-service").build(),
                publisher,
                postStore,
                executor
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void createPostRunsWorkflowOnPostExecutor() {
        server.expect(requestTo("http://user-service/internal/users/u001/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u001","status":"ACTIVE"}}
                        """, MediaType.APPLICATION_JSON));
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            return null;
        }).when(postStore).save(any());

        PostResponse actual = feedService.createPost(
                new CreatePostRequest("u001", "Async Java")
        );

        assertEquals("u001", actual.authorId());
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(workerThread.get().startsWith("post-async-test-"));
        verify(publisher).publishPostCreated(any());
        server.verify();
    }

    @Test
    void createPostRethrowsBusinessExceptionWithoutCompletionWrapper() {
        server.expect(requestTo("http://user-service/internal/users/u003/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u003","status":"INACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(new CreatePostRequest("u003", "Not allowed"))
        );

        assertEquals("Author is not active", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        server.verify();
    }
}
