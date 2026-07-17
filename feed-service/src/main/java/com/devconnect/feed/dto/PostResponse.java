package com.devconnect.feed.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PostResponse", description = "Feed post response")
public record PostResponse(
        @Schema(example = "5b990c7c-72b1-4af3-9f50-66c56d9ee94d")
        String postId,
        @Schema(example = "u001")
        String authorId,
        @Schema(example = "Spring Boot and Kafka")
        String content,
        @Schema(example = "2026-07-14T03:15:30.123")
        LocalDateTime createdAt
) {
}
