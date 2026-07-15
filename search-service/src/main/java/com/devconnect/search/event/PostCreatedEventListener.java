package com.devconnect.search.event;

import com.devconnect.search.service.PostSearchService;
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

    private final PostSearchService postSearchService;
    private final AtomicBoolean initialReplayPending = new AtomicBoolean(true);

    public PostCreatedEventListener(PostSearchService postSearchService) {
        this.postSearchService = postSearchService;
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
        log.info("Rebuilding search index from the beginning of partitions {}", partitions);
        callback.seekToBeginning(partitions);
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "search-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            postSearchService.indexPost(event.postId(), event.authorId(), event.content());
        }
    }
}
