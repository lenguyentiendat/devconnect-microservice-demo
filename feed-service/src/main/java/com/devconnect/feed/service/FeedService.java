package com.devconnect.feed.service;

import com.devconnect.feed.cache.CacheInvalidation;
import com.devconnect.feed.cache.CacheInvalidationPublisher;
import com.devconnect.feed.cache.CacheKeyFactory;
import com.devconnect.feed.cache.CacheService;
import com.devconnect.feed.cache.FeedRevisionService;
import com.devconnect.feed.client.UserServiceAdapter;
import com.devconnect.feed.config.CacheProperties;
import com.devconnect.feed.dto.ContentValidationResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.FeedPage;
import com.devconnect.feed.dto.FeedPageResponse;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.event.PostCreatedEvent;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.persistence.PostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;

@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    private static final String GLOBAL_FEED = "global";

    private final UserServiceAdapter userServiceAdapter;
    private final PostEventPublisher postEventPublisher;
    private final PostStore postStore;
    private final Executor postTaskExecutor;
    private final CacheService cacheService;
    private final CacheKeyFactory cacheKeyFactory;
    private final CacheProperties cacheProperties;
    private final FeedRevisionService feedRevisionService;
    private final CacheInvalidationPublisher invalidationPublisher;

    public FeedService(
            UserServiceAdapter userServiceAdapter,
            PostEventPublisher postEventPublisher,
            PostStore postStore,
            @Qualifier("postTaskExecutor") Executor postTaskExecutor,
            CacheService cacheService,
            CacheKeyFactory cacheKeyFactory,
            CacheProperties cacheProperties,
            FeedRevisionService feedRevisionService,
            CacheInvalidationPublisher invalidationPublisher
    ) {
        this.userServiceAdapter = userServiceAdapter;
        this.postEventPublisher = postEventPublisher;
        this.postStore = postStore;
        this.postTaskExecutor = postTaskExecutor;
        this.cacheService = cacheService;
        this.cacheKeyFactory = cacheKeyFactory;
        this.cacheProperties = cacheProperties;
        this.feedRevisionService = feedRevisionService;
        this.invalidationPublisher = invalidationPublisher;
    }

    public PostResponse createPost(CreatePostRequest request) {
        CompletableFuture<UserStatusResponse> authorStatusFuture =
                CompletableFuture.supplyAsync(
                        () -> getAuthorStatus(request.authorId()),
                        postTaskExecutor
                );
        CompletableFuture<ContentValidationResponse> contentValidationFuture =
                CompletableFuture.supplyAsync(
                        () -> validatePostContent(request.content()),
                        postTaskExecutor
                );

        try {
            PostCreationValidation validation = authorStatusFuture
                    .thenCombine(
                            contentValidationFuture,
                            PostCreationValidation::new
                    )
                    .join();
            validatePostCreation(validation);
            return createAndSavePost(request);
        } catch (CompletionException exception) {
            throw unwrapCompletionException(exception);
        }
    }

    private ContentValidationResponse validatePostContent(String content) {
        log.info("Validating content on thread: {}", Thread.currentThread().getName());

        if (content == null || content.isBlank()) {
            return new ContentValidationResponse(false, "Post content must not be blank");
        }

        if (content.length() > 5_000) {
            return new ContentValidationResponse(
                    false,
                    "Post content must not exceed 5000 characters"
            );
        }

        String normalizedContent = content.toLowerCase(Locale.ROOT);
        boolean containsProhibitedWord = List.of("spam", "scam").stream()
                .anyMatch(normalizedContent::contains);

        if (containsProhibitedWord) {
            return new ContentValidationResponse(
                    false,
                    "Post content contains prohibited words"
            );
        }

        return new ContentValidationResponse(true, null);
    }

    private void validatePostCreation(PostCreationValidation validation) {
        if (!"ACTIVE".equals(validation.authorStatus().status())) {
            throw new BusinessException("Author is not active");
        }

        if (!validation.contentValidation().allowed()) {
            throw new BusinessException(validation.contentValidation().reason());
        }
    }

    private PostResponse createAndSavePost(CreatePostRequest request) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                Instant.now().truncatedTo(MILLIS),
                UTC
        );
        PostResponse post = new PostResponse(
                UUID.randomUUID().toString(),
                request.authorId(),
                request.content(),
                createdAt
        );

        postStore.save(post);
        invalidatePostWrite(post);

        PostCreatedEvent event = new PostCreatedEvent(
                UUID.randomUUID().toString(),
                "POST_CREATED",
                post.postId(),
                post.authorId(),
                post.content(),
                createdAt
        );
        postEventPublisher.publishPostCreated(event);

        return post;
    }

    private RuntimeException unwrapCompletionException(CompletionException exception) {
        Throwable cause = exception.getCause();

        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }

        return new IllegalStateException("Post creation failed", cause);
    }

    public PostResponse getPostById(String postId) {
        if (!cacheProperties.enabled()) {
            return loadPost(postId);
        }
        return cacheService.getOrLoad(
                cacheKeyFactory.post(postId),
                PostResponse.class,
                cacheProperties.postTtls(),
                () -> loadPost(postId)
        );
    }

    private PostResponse loadPost(String postId) {
        return postStore.findById(postId)
                .orElseThrow(() -> new BusinessException("Post not found"));
    }

    public List<PostResponse> getPosts() {
        return postStore.findAll();
    }

    public FeedPageResponse getPosts(int pageSize, LocalDateTime lastCreatedAt, String lastPostId) {
        UUID cursorPostId = validatePageRequest(pageSize, lastCreatedAt, lastPostId);

        if (!cacheProperties.enabled()) {
            return loadPage(pageSize, lastCreatedAt, cursorPostId, 0L);
        }

        long revision = feedRevisionService.current(GLOBAL_FEED);
        if (revision <= 0) {
            return loadPage(pageSize, lastCreatedAt, cursorPostId, 0L);
        }

        FeedPageResponse cachedPage = cacheService.getOrLoad(
                cacheKeyFactory.feedPage(GLOBAL_FEED, revision, pageSize, lastCreatedAt, lastPostId),
                FeedPageResponse.class,
                cacheProperties.pageTtls(),
                () -> loadPage(pageSize, lastCreatedAt, cursorPostId, revision)
        );
        return withRequestMetadata(cachedPage, pageSize, revision);
    }

    private UUID validatePageRequest(int pageSize, LocalDateTime lastCreatedAt, String lastPostId) {
        if (pageSize < 1 || pageSize > cacheProperties.feedMaximumPageSize()) {
            throw new BusinessException(
                    "pageSize must be between 1 and " + cacheProperties.feedMaximumPageSize()
            );
        }
        if ((lastCreatedAt == null) != (lastPostId == null || lastPostId.isBlank())) {
            throw new BusinessException("lastCreatedAt and lastPostId must be provided together");
        }
        if (lastCreatedAt == null) return null;
        try {
            return UUID.fromString(lastPostId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Invalid lastPostId");
        }
    }

    private FeedPageResponse loadPage(int pageSize, LocalDateTime lastCreatedAt, UUID lastPostId, long revision) {
        FeedPage page = postStore.findPage(pageSize, lastCreatedAt == null ? null : lastCreatedAt.toInstant(UTC), lastPostId);
        PostResponse last = page.hasNext() ? page.items().getLast() : null;
        return new FeedPageResponse(
                page.items(),
                pageSize,
                page.hasNext(),
                last == null ? null : last.createdAt(),
                last == null ? null : last.postId(),
                revision
        );
    }

    private FeedPageResponse withRequestMetadata(
            FeedPageResponse page,
            int pageSize,
            long revision
    ) {
        return new FeedPageResponse(
                page.items(),
                pageSize,
                page.hasNext(),
                page.nextLastCreatedAt(),
                page.nextLastPostId(),
                revision
        );
    }

    private void invalidatePostWrite(PostResponse post) {
        if (!cacheProperties.enabled()) {
            return;
        }
        String postKey = cacheKeyFactory.post(post.postId());
        long revision = advanceRevision();

        if (revision > 0) {
            runCacheOperation(
                    "invalidation publication",
                    () -> invalidationPublisher.publish(new CacheInvalidation(postKey))
            );
        }
        runCacheOperation("post cache write", () -> cacheService.addCacheByKey(
                postKey,
                post,
                cacheProperties.postTtls()
        ));
    }

    private long advanceRevision() {
        try {
            return feedRevisionService.advance(GLOBAL_FEED);
        } catch (RuntimeException exception) {
            log.warn("Cache revision advance failed: {}", exception.getClass().getSimpleName());
            return 0L;
        }
    }

    private void runCacheOperation(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Cache {} failed: {}", operation, exception.getClass().getSimpleName());
        }
    }

    private UserStatusResponse getAuthorStatus(String authorId) {
        log.info("Getting author status on thread: {}", Thread.currentThread().getName());
        return userServiceAdapter.getUserStatus(authorId);
    }

    private record PostCreationValidation(
            UserStatusResponse authorStatus,
            ContentValidationResponse contentValidation
    ) {
    }
}
