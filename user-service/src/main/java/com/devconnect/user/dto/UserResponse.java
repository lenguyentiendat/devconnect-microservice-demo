package com.devconnect.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserResponse", description = "Public user profile response")
public record UserResponse(
        @Schema(example = "u004")
        String userId,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
        String status,
        @Schema(example = "user@example.com", nullable = true)
        String email
) {
}
