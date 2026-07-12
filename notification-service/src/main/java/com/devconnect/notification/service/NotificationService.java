package com.devconnect.notification.service;

import com.devconnect.notification.dto.NotificationResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {

    private final Map<String, NotificationResponse> notifications = new ConcurrentHashMap<>();
    private final Map<String, Boolean> processedEventIds = new ConcurrentHashMap<>();

    public void createPostCreatedNotification(String eventId, String authorId, String postId) {
        if (processedEventIds.putIfAbsent(eventId, Boolean.TRUE) != null) {
            return;
        }

        NotificationResponse notification = new NotificationResponse(
                UUID.randomUUID().toString(),
                authorId,
                "Post created",
                "Your post " + postId + " was created successfully",
                LocalDateTime.now()
        );
        notifications.put(notification.notificationId(), notification);
    }

    public List<NotificationResponse> getNotificationsByUser(String userId) {
        return notifications.values().stream()
                .filter(notification -> notification.userId().equals(userId))
                .toList();
    }
}
