package com.devconnect.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SearchPostResponse", description = "Post projection returned by search")
public record SearchPostResponse(
        @Schema(example = "5b990c7c-72b1-4af3-9f50-66c56d9ee94d")
        String postId,
        @Schema(example = "u001")
        String authorId,
        @Schema(example = "Spring Boot and Kafka")
        String content
) {
}
