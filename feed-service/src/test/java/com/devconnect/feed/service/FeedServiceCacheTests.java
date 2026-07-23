package com.devconnect.feed.service;

import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.client.UserServiceAdapter;
import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.FeedPage;
import com.devconnect.feed.dto.FeedPageResponse;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedServiceCacheTests {
    private final PostResponse post = new PostResponse("550e8400-e29b-41d4-a716-446655440000", "user", "Cached", LocalDateTime.parse("2026-07-22T10:00:00"));
    private PostStore store;
    private CacheService cache;
    private FeedRevisionService revisions;
    private CacheKeyFactory keys;
    private FeedService service;

    @BeforeEach
    void setUp() {
        store = mock(PostStore.class);
        cache = mock(CacheService.class);
        revisions = mock(FeedRevisionService.class);
        CacheProperties properties = properties();
        keys = new CacheKeyFactory(properties);
        service = new FeedService(mock(UserServiceAdapter.class), mock(PostEventPublisher.class), store, Runnable::run, cache, keys, properties, revisions, mock(CacheInvalidationPublisher.class));
    }

    @Test
    void firstPageUsesRevisionedFirstCursorKey() {
        when(revisions.current("global")).thenReturn(7L);
        when(cache.getOrLoad(any(), eq(FeedPageResponse.class), any(), any())).thenAnswer(i -> i.<Supplier<FeedPageResponse>>getArgument(3).get());
        when(store.findPage(eq(20), isNull(), isNull())).thenReturn(new FeedPage(List.of(post), true));

        FeedPageResponse page = service.getPosts(20, null, null);

        assertThat(page.nextLastCreatedAt()).isEqualTo(post.createdAt());
        assertThat(page.nextLastPostId()).isEqualTo(post.postId());
        verify(cache).getOrLoad(eq(keys.feedPage("global", 7L, 20, null, null)), eq(FeedPageResponse.class), any(), any());
    }

    @Test
    void laterPageUsesCursorPairAndConvertsTimestampToUtc() {
        LocalDateTime cursorTime = LocalDateTime.parse("2026-07-22T09:00:00");
        String cursorId = UUID.randomUUID().toString();
        when(revisions.current("global")).thenReturn(7L);
        when(cache.getOrLoad(any(), eq(FeedPageResponse.class), any(), any())).thenAnswer(i -> i.<Supplier<FeedPageResponse>>getArgument(3).get());
        when(store.findPage(eq(20), eq(cursorTime.toInstant(java.time.ZoneOffset.UTC)), eq(UUID.fromString(cursorId)))).thenReturn(new FeedPage(List.of(post), false));

        FeedPageResponse page = service.getPosts(20, cursorTime, cursorId);

        assertThat(page.nextLastCreatedAt()).isNull();
        verify(cache).getOrLoad(eq(keys.feedPage("global", 7L, 20, cursorTime, cursorId)), eq(FeedPageResponse.class), any(), any());
    }

    @Test
    void partialCursorIsRejectedBeforeCacheAccess() {
        assertThatThrownBy(() -> service.getPosts(20, LocalDateTime.now(), null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(cache, revisions);
    }

    @Test
    void invalidPostIdIsRejectedBeforeCacheAccess() {
        assertThatThrownBy(() -> service.getPosts(20, LocalDateTime.now(), "not-a-uuid")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(cache, revisions);
    }

    private static CacheProperties properties() {
        return new CacheProperties(true, "local", 100, Duration.ofSeconds(45), Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofMinutes(1), 20, 100, 0, 100, "devconnect:cache:invalidation");
    }
}
