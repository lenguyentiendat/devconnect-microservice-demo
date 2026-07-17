package com.devconnect.notification.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NotificationResponse", description = "Notification generated from a post event")
public record NotificationResponse(
        @Schema(example = "f3f0d53a-9b18-4cb4-9f2b-7f3be2e4c5c6")
        String notificationId,
        @Schema(example = "u001")
        String userId,
        @Schema(example = "Post created")
        String title,
        @Schema(example = "Your post 5b990c7c-72b1-4af3-9f50-66c56d9ee94d was created successfully")
        String message,
        @Schema(example = "2026-07-14T03:15:30.123")
        LocalDateTime createdAt
) {
}
