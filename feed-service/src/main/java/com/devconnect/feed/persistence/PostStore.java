package com.devconnect.feed.persistence;

import com.devconnect.feed.dto.FeedPage;
import com.devconnect.feed.dto.PostResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostStore {

    void save(PostResponse post);

    Optional<PostResponse> findById(String postId);

    FeedPage findPage(int pageSize, Instant lastCreatedAt, UUID lastPostId);

    List<PostResponse> findAll();
}
