package com.devconnect.feed.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreatePostRequest", description = "Payload used to create a feed post")
public record CreatePostRequest(

        @NotBlank(message = "authorId is required")
        @Schema(description = "Author user identifier", example = "u001")
        String authorId,

        @NotBlank(message = "content is required")
        @Schema(description = "Post text content", example = "Spring Boot and Kafka")
        String content
) {
}
