package com.devconnect.feed.service;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import com.devconnect.feed.event.PostCreatedEvent;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.persistence.PostStore;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;

@Service
public class FeedService {

    private final RestClient userServiceRestClient;
    private final PostEventPublisher postEventPublisher;
    private final PostStore postStore;

    public FeedService(
            RestClient userServiceRestClient,
            PostEventPublisher postEventPublisher,
            PostStore postStore
    ) {
        this.userServiceRestClient = userServiceRestClient;
        this.postEventPublisher = postEventPublisher;
        this.postStore = postStore;
    }

    public PostResponse createPost(CreatePostRequest request) {
        UserStatusResponse authorStatus = getAuthorStatus(request.authorId());

        if (!"ACTIVE".equals(authorStatus.status())) {
            throw new BusinessException("Author is not active");
        }

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

    public PostResponse getPostById(String postId) {
        return postStore.findById(postId)
                .orElseThrow(() -> new BusinessException("Post not found"));
    }

    public List<PostResponse> getPosts() {
        return postStore.findAll();
    }

    private UserStatusResponse getAuthorStatus(String authorId) {
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
}
