package com.devconnect.feed.controller;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.service.AsyncPostService;
import com.devconnect.feed.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedController.class)
class FeedControllerAsyncTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedService feedService;

    @MockitoBean
    private AsyncPostService asyncPostService;

    @Test
    void createPostStartsAsyncRequestAndReturnsCompletedResult() throws Exception {
        PostResponse postResponse = new PostResponse(
                "post-1", "u001", "Async Java", LocalDateTime.now()
        );
        when(asyncPostService.createPost(any(CreatePostRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(postResponse));

        MvcResult pendingResult = mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u001",
                                  "content": "Async Java"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pendingResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.postId").value("post-1"));
    }

    @Test
    void createPostDispatchesAsyncBusinessExceptionToExistingHandler() throws Exception {
        when(asyncPostService.createPost(any(CreatePostRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new BusinessException("Author is not active")
                ));

        MvcResult pendingResult = mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u003",
                                  "content": "Not allowed"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pendingResult))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Author is not active"));
    }
}
