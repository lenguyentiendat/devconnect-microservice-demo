package com.devconnect.feed.service;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import com.devconnect.feed.event.PostCreatedEvent;
import com.devconnect.feed.event.PostEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeedService {

    private final RestClient userServiceRestClient;
    private final PostEventPublisher postEventPublisher;

    private final Map<String, PostResponse> posts = new ConcurrentHashMap<>();

    public FeedService(RestClient userServiceRestClient, PostEventPublisher postEventPublisher) {
        this.userServiceRestClient = userServiceRestClient;
        this.postEventPublisher = postEventPublisher;
    }

    public PostResponse createPost(CreatePostRequest request) {
        UserStatusResponse authorStatus = getAuthorStatus(request.authorId());

        if (!"ACTIVE".equals(authorStatus.status())) {
            throw new BusinessException("Author is not active");
        }

        PostResponse post = new PostResponse(
                UUID.randomUUID().toString(),
                request.authorId(),
                request.content(),
                LocalDateTime.now()
        );

        posts.put(post.postId(), post);

        PostCreatedEvent event = new PostCreatedEvent(
                UUID.randomUUID().toString(),
                "POST_CREATED",
                post.postId(),
                post.authorId(),
                post.content(),
                LocalDateTime.now()
        );
        postEventPublisher.publishPostCreated(event);

        return post;
    }

    public PostResponse getPostById(String postId) {
        PostResponse post = posts.get(postId);

        if (post == null) {
            throw new BusinessException("Post not found");
        }

        return post;
    }

    public List<PostResponse> getPosts() {
        return posts.values().stream()
                .sorted(Comparator.comparing(PostResponse::createdAt).reversed())
                .toList();
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
