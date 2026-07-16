package com.devconnect.feed.controller;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.service.FeedService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feed/posts")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @PostMapping
    public ApiResponse<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest request
    ) {
        PostResponse response = feedService.createPost(request);
        return ApiResponse.success("Post created successfully", response);
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
