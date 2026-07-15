package com.devconnect.notification.event;

import com.devconnect.notification.service.NotificationService;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PostCreatedEventListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventListener.class);

    private final NotificationService notificationService;
    private final AtomicBoolean initialReplayPending = new AtomicBoolean(true);

    public PostCreatedEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onPartitionsAssigned(
            Map<TopicPartition, Long> assignments,
            ConsumerSeekCallback callback
    ) {
        if (assignments.isEmpty() || !initialReplayPending.compareAndSet(true, false)) {
            return;
        }

        List<TopicPartition> partitions = List.copyOf(assignments.keySet());
        log.info("Rebuilding notification read model from the beginning of partitions {}", partitions);
        callback.seekToBeginning(partitions);
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "notification-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            notificationService.createPostCreatedNotification(event.eventId(), event.authorId(), event.postId());
        }
    }
}
