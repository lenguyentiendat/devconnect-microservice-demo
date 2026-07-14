package com.devconnect.feed.persistence;

import com.devconnect.feed.dto.PostResponse;

import java.util.List;
import java.util.Optional;

public interface PostStore {

    void save(PostResponse post);

    Optional<PostResponse> findById(String postId);

    List<PostResponse> findAll();
}
