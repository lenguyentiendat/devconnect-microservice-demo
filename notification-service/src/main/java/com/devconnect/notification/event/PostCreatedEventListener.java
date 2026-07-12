package com.devconnect.notification.event;

import com.devconnect.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PostCreatedEventListener {

    private final NotificationService notificationService;

    public PostCreatedEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "notification-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            notificationService.createPostCreatedNotification(event.eventId(), event.authorId(), event.postId());
        }
    }
}
