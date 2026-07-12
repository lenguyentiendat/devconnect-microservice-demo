package com.devconnect.search.event;

import com.devconnect.search.service.PostSearchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PostCreatedEventListener {

    private final PostSearchService postSearchService;

    public PostCreatedEventListener(PostSearchService postSearchService) {
        this.postSearchService = postSearchService;
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "search-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            postSearchService.indexPost(event.postId(), event.authorId(), event.content());
        }
    }
}
