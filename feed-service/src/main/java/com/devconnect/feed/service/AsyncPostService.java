package com.devconnect.feed.service;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AsyncPostService {

    private final FeedService feedService;

    public AsyncPostService(FeedService feedService) {
        this.feedService = feedService;
    }

    @Async("postTaskExecutor")
    public CompletableFuture<PostResponse> createPost(CreatePostRequest request) {
        PostResponse post = feedService.createPost(request);
        return CompletableFuture.completedFuture(post);
    }
}
