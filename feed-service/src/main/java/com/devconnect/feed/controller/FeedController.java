package com.devconnect.feed.controller;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.service.AsyncPostService;
import com.devconnect.feed.service.FeedService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/feed/posts")
public class FeedController {

    private final FeedService feedService;
    private final AsyncPostService asyncPostService;

    public FeedController(FeedService feedService, AsyncPostService asyncPostService) {
        this.feedService = feedService;
        this.asyncPostService = asyncPostService;
    }

    @PostMapping
    public CompletableFuture<ApiResponse<PostResponse>> createPost(
            @Valid @RequestBody CreatePostRequest request
    ) {
        return asyncPostService.createPost(request)
                .thenApply(response -> ApiResponse.success("Post created successfully", response));
    }

    @GetMapping
    public ApiResponse<List<PostResponse>> getPosts() {
        return ApiResponse.success("Posts found", feedService.getPosts());
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostResponse> getPostById(@PathVariable String postId) {
        PostResponse response = feedService.getPostById(postId);

        return ApiResponse.success("Post found", response);
    }
}
