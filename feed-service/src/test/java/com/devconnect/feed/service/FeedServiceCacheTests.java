package com.devconnect.feed.service;

import com.devconnect.feed.cache.CacheInvalidation;
import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.client.UserServiceAdapter;
import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.FeedPage;
import com.devconnect.feed.dto.FeedPageResponse;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.paging.PageTokenCodec;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class FeedServiceCacheTests {

    private static final String GLOBAL_FEED = "global";

    private UserServiceAdapter userServiceAdapter;
    private PostEventPublisher postEventPublisher;
    private PostStore postStore;
    private CacheService cacheService;
    private FeedRevisionService revisions;
    private CacheInvalidationPublisher invalidationPublisher;
    private CacheProperties properties;
    private CacheKeyFactory cacheKeys;
    private FeedService feedService;
    private final String postId = "post-123";
    private final PostResponse post = new PostResponse(
            postId,
            "user-123",
            "Cached post",
            LocalDateTime.parse("2026-07-22T10:00:00")
    );

    @BeforeEach
    void setUp() {
        userServiceAdapter = mock(UserServiceAdapter.class);
        postEventPublisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        cacheService = mock(CacheService.class);
        revisions = mock(FeedRevisionService.class);
        invalidationPublisher = mock(CacheInvalidationPublisher.class);
        properties = cacheProperties();
        cacheKeys = new CacheKeyFactory(properties);
        feedService = new FeedService(
                userServiceAdapter,
                postEventPublisher,
                postStore,
                Runnable::run,
                cacheService,
                cacheKeys,
                properties,
                revisions,
                invalidationPublisher,
                new PageTokenCodec("test-page-token-secret")
        );
    }

    @Test
    void getPostByIdUsesPostKeyAndLoadsStoreOnlyOnMiss() {
        when(postStore.findById(postId)).thenReturn(Optional.of(post));
        when(cacheService.getOrLoad(eq(cacheKeys.post(postId)), eq(PostResponse.class), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<PostResponse>>getArgument(3).get());

        assertThat(feedService.getPostById(postId)).isEqualTo(post);

        verify(postStore).findById(postId);
    }

    @Test
    void pageTwoWithoutTokenIsRejected() {
        assertThatThrownBy(() -> feedService.getPosts(2, 20, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("pageToken is required when pageNum is greater than 1");
    }

    @Test
    void pageNumberAndSizeAreValidated() {
        assertThatThrownBy(() -> feedService.getPosts(0, 20, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> feedService.getPosts(1, 0, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> feedService.getPosts(1, 101, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pageReadUsesRevisionedKeyAndReturnsNextToken() {
        when(revisions.current(GLOBAL_FEED)).thenReturn(7L);
        when(cacheService.getOrLoad(any(), eq(FeedPageResponse.class), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<FeedPageResponse>>getArgument(3).get());
        when(postStore.findPage(eq(20), isNull()))
                .thenReturn(new FeedPage(List.of(post), ByteBuffer.wrap(new byte[]{1})));

        FeedPageResponse page = feedService.getPosts(1, 20, null);

        assertThat(page.nextPageToken()).isNotBlank();
        assertThat(page.feedRevision()).isEqualTo(7L);
        verify(cacheService).getOrLoad(
                eq(cacheKeys.feedPage(GLOBAL_FEED, 7L, 20, null)),
                eq(FeedPageResponse.class),
                any(),
                any()
        );
    }

    @Test
    void unavailableRevisionBypassesPageCache() {
        when(revisions.current(GLOBAL_FEED)).thenReturn(0L);
        when(postStore.findPage(eq(20), isNull()))
                .thenReturn(new FeedPage(List.of(post), null));

        FeedPageResponse page = feedService.getPosts(1, 20, null);

        assertThat(page.feedRevision()).isZero();
        verify(cacheService, never()).getOrLoad(any(), eq(FeedPageResponse.class), any(), any());
    }

    @Test
    void createPostAdvancesRevisionBeforePublishingKafkaAndCachesExactPost() {
        when(userServiceAdapter.getUserStatus("user-123"))
                .thenReturn(new UserStatusResponse("user-123", "ACTIVE"));
        when(revisions.advance(GLOBAL_FEED)).thenReturn(8L);

        PostResponse created = feedService.createPost(new CreatePostRequest("user-123", "Created post"));

        String exactKey = cacheKeys.post(created.postId());
        InOrder ordered = inOrder(revisions, cacheService, invalidationPublisher, postEventPublisher);
        ordered.verify(revisions).advance(GLOBAL_FEED);
        ordered.verify(invalidationPublisher).publish(new CacheInvalidation(exactKey));
        ordered.verify(cacheService).addCacheByKey(eq(exactKey), eq(created), any());
        ordered.verify(postEventPublisher).publishPostCreated(any());
    }

    @Test
    void cacheFailureDoesNotFailCreatedPostOrKafkaPublication() {
        when(userServiceAdapter.getUserStatus("user-123"))
                .thenReturn(new UserStatusResponse("user-123", "ACTIVE"));
        when(revisions.advance(GLOBAL_FEED)).thenThrow(new IllegalStateException("Redis unavailable"));
        doThrow(new IllegalStateException("Cache unavailable"))
                .when(cacheService).addCacheByKey(any(), any(), any());

        assertThat(feedService.createPost(new CreatePostRequest("user-123", "Created post"))).isNotNull();

        verify(postStore).save(any());
        verify(postEventPublisher).publishPostCreated(any());
    }

    @Test
    void createPostSkipsPublicationWhenRevisionAdvanceIsUnavailable() {
        when(userServiceAdapter.getUserStatus("user-123"))
                .thenReturn(new UserStatusResponse("user-123", "ACTIVE"));
        when(revisions.advance(GLOBAL_FEED)).thenReturn(0L);

        feedService.createPost(new CreatePostRequest("user-123", "Created post"));

        verify(invalidationPublisher, never()).publish(any());
    }

    @Test
    void disabledCacheBypassesRevisionPublicationAndAllCacheOperations() {
        properties = cacheProperties(false);
        cacheKeys = new CacheKeyFactory(properties);
        feedService = new FeedService(
                userServiceAdapter,
                postEventPublisher,
                postStore,
                Runnable::run,
                cacheService,
                cacheKeys,
                properties,
                revisions,
                invalidationPublisher,
                new PageTokenCodec("test-page-token-secret")
        );
        when(postStore.findById(postId)).thenReturn(Optional.of(post));
        when(postStore.findPage(eq(20), isNull())).thenReturn(new FeedPage(List.of(post), null));
        when(userServiceAdapter.getUserStatus("user-123"))
                .thenReturn(new UserStatusResponse("user-123", "ACTIVE"));

        assertThat(feedService.getPostById(postId)).isEqualTo(post);
        assertThat(feedService.getPosts(1, 20, null).feedRevision()).isZero();
        assertThat(feedService.createPost(new CreatePostRequest("user-123", "Created post"))).isNotNull();

        verifyNoInteractions(cacheService, revisions, invalidationPublisher);
    }

    @Test
    void invalidPageTokenIsRejectedBeforeCacheOrRevisionAccess() {
        assertThatThrownBy(() -> feedService.getPosts(2, 20, "tampered"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid page token");

        verifyNoInteractions(cacheService, revisions);
    }

    @Test
    void blankFirstPageTokenUsesTheFirstPageCacheKeyAndLoader() {
        when(revisions.current(GLOBAL_FEED)).thenReturn(7L);
        when(cacheService.getOrLoad(any(), eq(FeedPageResponse.class), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<FeedPageResponse>>getArgument(3).get());
        when(postStore.findPage(eq(20), isNull()))
                .thenReturn(new FeedPage(List.of(post), null));

        feedService.getPosts(1, 20, "  ");

        verify(cacheService).getOrLoad(
                eq(cacheKeys.feedPage(GLOBAL_FEED, 7L, 20, null)),
                eq(FeedPageResponse.class),
                any(),
                any()
        );
        verify(postStore).findPage(eq(20), isNull());
    }

    @Test
    void cachedPageIsReturnedWithTheRequestedPageNumber() {
        String token = new PageTokenCodec("test-page-token-secret")
                .encode(ByteBuffer.wrap(new byte[]{1}));
        when(revisions.current(GLOBAL_FEED)).thenReturn(7L);
        when(cacheService.getOrLoad(any(), eq(FeedPageResponse.class), any(), any()))
                .thenReturn(new FeedPageResponse(List.of(post), 1, 20, false, null, 7L));

        FeedPageResponse page = feedService.getPosts(2, 20, token);

        assertThat(page.pageNum()).isEqualTo(2);
        verify(postStore, never()).findPage(any(Integer.class), any());
    }

    private static CacheProperties cacheProperties() {
        return cacheProperties(true);
    }

    private static CacheProperties cacheProperties(boolean enabled) {
        return new CacheProperties(
                enabled,
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
