package com.devconnect.search.dto;

public record SearchPostResponse(
        String postId,
        String authorId,
        String content
) {
}
