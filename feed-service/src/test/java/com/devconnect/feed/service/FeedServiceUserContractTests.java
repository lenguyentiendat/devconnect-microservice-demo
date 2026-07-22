package com.devconnect.feed.service;

import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.client.UserServiceAdapter;
import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import com.devconnect.feed.paging.PageTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceUserContractTests {

    private UserServiceAdapter userServiceAdapter;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        userServiceAdapter = mock(UserServiceAdapter.class);
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        CacheProperties cacheProperties = cacheProperties();
        feedService = new FeedService(
                userServiceAdapter,
                publisher,
                postStore,
                Runnable::run,
                mock(CacheService.class),
                new CacheKeyFactory(cacheProperties),
                cacheProperties,
                mock(FeedRevisionService.class),
                mock(CacheInvalidationPublisher.class),
                new PageTokenCodec("test-page-token-secret")
        );
    }

    @Test
    void activeUserContractStillAllowsPostCreation() {
        when(userServiceAdapter.getUserStatus("u004"))
                .thenReturn(new UserStatusResponse("u004", "ACTIVE"));

        var post = feedService.createPost(new CreatePostRequest("u004", "Still compatible"));

        assertEquals("u004", post.authorId());
        verify(postStore).save(post);
        verify(publisher).publishPostCreated(any());
        verify(userServiceAdapter).getUserStatus("u004");
    }

    @Test
    void inactiveUserContractStillRejectsPostCreation() {
        when(userServiceAdapter.getUserStatus("u004"))
                .thenReturn(new UserStatusResponse("u004", "INACTIVE"));

        var exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(new CreatePostRequest("u004", "Blocked"))
        );

        assertEquals("Author is not active", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        verify(userServiceAdapter).getUserStatus("u004");
    }

    private static CacheProperties cacheProperties() {
        return new CacheProperties(
                true, "local", 100,
                Duration.ofSeconds(45), Duration.ofMinutes(5),
                Duration.ofSeconds(10), Duration.ofMinutes(1),
                20, 100, 0, 100,
                "devconnect:cache:invalidation", "page-token-secret"
        );
    }
}
