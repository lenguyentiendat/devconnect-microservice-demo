package com.devconnect.feed.controller;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.FeedPageResponse;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedController.class)
class FeedControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedService feedService;

    @Test
    void createPostReturnsSynchronousSuccessResponse() throws Exception {
        PostResponse response = new PostResponse(
                "post-1", "u001", "Async Java", LocalDateTime.now()
        );
        when(feedService.createPost(any(CreatePostRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u001",
                                  "content": "Async Java"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value("post-1"))
                .andExpect(jsonPath("$.authorId").value("u001"))
                .andExpect(jsonPath("$.content").value("Async Java"))
                .andExpect(jsonPath("$.success").doesNotExist());

        verify(feedService).createPost(any(CreatePostRequest.class));
    }

    @Test
    void createPostPassesBusinessExceptionToExistingHandler() throws Exception {
        when(feedService.createPost(any(CreatePostRequest.class)))
                .thenThrow(new BusinessException("Author is not active"));

        mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u003",
                                  "content": "Not allowed"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Author is not active"));
    }

    @Test
    void getPostsReturnsCursorPagedEnvelope() throws Exception {
        PostResponse post = new PostResponse("post-1", "u001", "Paged post", LocalDateTime.now());
        when(feedService.getPosts(20, null, null)).thenReturn(
                new FeedPageResponse(java.util.List.of(post), 20, true, post.createdAt(), post.postId(), 1L)
        );

        mockMvc.perform(get("/api/feed/posts")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].postId").value("post-1"))
                .andExpect(jsonPath("$.data.nextLastPostId").value("post-1"));
    }

    @Test
    void getPostsRejectsInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/feed/posts").param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getPostsReturnsBadRequestEnvelopeForPartialCursor() throws Exception {
        when(feedService.getPosts(20, LocalDateTime.now(), null))
                .thenThrow(new BusinessException("lastCreatedAt and lastPostId must be provided together"));

        mockMvc.perform(get("/api/feed/posts")
                        .param("pageSize", "20")
                        .param("lastCreatedAt", LocalDateTime.now().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("lastCreatedAt and lastPostId must be provided together"));
    }
}
