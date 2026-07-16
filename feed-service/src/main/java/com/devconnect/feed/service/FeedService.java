package com.devconnect.feed.service;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.ContentValidationResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import com.devconnect.feed.event.PostCreatedEvent;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.persistence.PostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    private final RestClient userServiceRestClient;
    private final PostEventPublisher postEventPublisher;
    private final PostStore postStore;
    private final Executor postTaskExecutor;

    public FeedService(
            RestClient userServiceRestClient,
            PostEventPublisher postEventPublisher,
            PostStore postStore,
            @Qualifier("postTaskExecutor") Executor postTaskExecutor
    ) {
        this.userServiceRestClient = userServiceRestClient;
        this.postEventPublisher = postEventPublisher;
        this.postStore = postStore;
        this.postTaskExecutor = postTaskExecutor;
    }

    public PostResponse createPost(CreatePostRequest request) {
        CompletableFuture<UserStatusResponse> authorStatusFuture =
                CompletableFuture.supplyAsync(
                        () -> 1getAuthorStatus(request.authorId()),
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
        return postStore.findById(postId)
                .orElseThrow(() -> new BusinessException("Post not found"));
    }

    public List<PostResponse> getPosts() {
        return postStore.findAll();
    }

    private UserStatusResponse getAuthorStatus(String authorId) {
        log.info("Getting author status on thread: {}", Thread.currentThread().getName());

        try {
            ApiResponse<UserStatusResponse> response = userServiceRestClient
                    .get()
                    .uri("/internal/users/{userId}/status", authorId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, responseBody) -> {
                        throw new BusinessException("Author not found");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseBody) -> {
                        throw new DownstreamServiceException("User Service returned server error");
                    })
                    .body(new ParameterizedTypeReference<ApiResponse<UserStatusResponse>>() {});

            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new BusinessException("Author not found");
            }

            return response.getData();

        } catch (BusinessException ex) {
            throw ex;
        } catch (DownstreamServiceException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new DownstreamServiceException("Failed to call User Service", ex);
        }
    }

    private record PostCreationValidation(
            UserStatusResponse authorStatus,
            ContentValidationResponse contentValidation
    ) {
    }
}
