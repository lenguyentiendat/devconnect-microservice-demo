package com.devconnect.feed.event;

import java.time.LocalDateTime;

public record PostCreatedEvent(
        String eventId,
        String eventType,
        String postId,
        String authorId,
        String content,
        LocalDateTime occurredAt
) {
}
