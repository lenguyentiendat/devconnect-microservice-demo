package com.devconnect.notification.dto;

import java.time.LocalDateTime;

public record NotificationResponse(
        String notificationId,
        String userId,
        String title,
        String message,
        LocalDateTime createdAt
) {
}
