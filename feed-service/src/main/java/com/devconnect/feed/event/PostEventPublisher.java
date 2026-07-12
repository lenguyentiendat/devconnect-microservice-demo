package com.devconnect.feed.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PostEventPublisher.class);

    private final KafkaTemplate<String, PostCreatedEvent> kafkaTemplate;
    private final String postEventsTopic;

    public PostEventPublisher(
            KafkaTemplate<String, PostCreatedEvent> kafkaTemplate,
            @Value("${app.kafka.topics.post-events}") String postEventsTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.postEventsTopic = postEventsTopic;
    }

    public void publishPostCreated(PostCreatedEvent event) {
        try {
            kafkaTemplate.send(postEventsTopic, event.postId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish POST_CREATED event for post {}", event.postId(), ex);
                            return;
                        }

                        var metadata = result.getRecordMetadata();
                        log.info("Published POST_CREATED event. topic={}, partition={}, offset={}",
                                metadata.topic(), metadata.partition(), metadata.offset());
                    });
        } catch (RuntimeException ex) {
            // Creating a post must not fail merely because Kafka cannot accept an event now.
            log.error("Could not start publishing POST_CREATED event for post {}", event.postId(), ex);
        }
    }
}
