package com.devconnect.feed.controller;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.service.FeedService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feed/posts")
@Tag(name = "Feed Posts", description = "Create and read posts from the feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @PostMapping
    @Operation(summary = "Create post", description = "Create a post after validating the author status and content.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Post created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or business rule failure"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "User Service unavailable"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error")
    })
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest request
    ) {
        PostResponse response = feedService.createPost(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List posts", description = "Return all posts currently available in the feed read model.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Posts found")
    public ApiResponse<List<PostResponse>> getPosts() {
        return ApiResponse.success("Posts found", feedService.getPosts());
    }

    @GetMapping("/{postId}")
    @Operation(summary = "Get post", description = "Return one post by its identifier.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Post found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Post lookup failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error")
    })
    public ApiResponse<PostResponse> getPostById(
            @Parameter(description = "Post UUID", example = "5b990c7c-72b1-4af3-9f50-66c56d9ee94d")
            @PathVariable String postId
    ) {
        PostResponse response = feedService.getPostById(postId);

        return ApiResponse.success("Post found", response);
    }
}
