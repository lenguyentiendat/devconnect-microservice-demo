package com.devconnect.feed.service;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedServiceUserContractTests {

    private MockRestServiceServer server;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        feedService = new FeedService(
                builder.baseUrl("http://user-service").build(),
                publisher,
                postStore
        );
    }

    @Test
    void activeUserContractStillAllowsPostCreation() {
        server.expect(requestTo("http://user-service/internal/users/u004/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u004","status":"ACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        var post = feedService.createPost(new CreatePostRequest("u004", "Still compatible"));

        assertEquals("u004", post.authorId());
        verify(postStore).save(post);
        verify(publisher).publishPostCreated(any());
        server.verify();
    }

    @Test
    void inactiveUserContractStillRejectsPostCreation() {
        server.expect(requestTo("http://user-service/internal/users/u004/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u004","status":"INACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        var exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(new CreatePostRequest("u004", "Blocked"))
        );

        assertEquals("Author is not active", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        server.verify();
    }
}
