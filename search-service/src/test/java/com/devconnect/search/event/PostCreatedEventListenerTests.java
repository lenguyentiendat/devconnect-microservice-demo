package com.devconnect.search.event;

import com.devconnect.search.service.PostSearchService;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PostCreatedEventListenerTests {

    private PostSearchService postSearchService;
    private PostCreatedEventListener listener;

    @BeforeEach
    void setUp() {
        postSearchService = mock(PostSearchService.class);
        listener = new PostCreatedEventListener(postSearchService);
    }

    @Test
    void seeksAllPartitionsToBeginningOnFirstAssignment() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition first = new TopicPartition("post-events", 0);
        TopicPartition second = new TopicPartition("post-events", 1);

        listener.onPartitionsAssigned(Map.of(first, 2L, second, 4L), callback);

        verify(callback).seekToBeginning(argThat(partitions ->
                partitions.size() == 2 && partitions.containsAll(List.of(first, second))));
    }

    @Test
    void doesNotSeekAgainOnLaterAssignment() {
        ConsumerSeekCallback firstCallback = mock(ConsumerSeekCallback.class);
        ConsumerSeekCallback secondCallback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(partition, 2L), firstCallback);
        listener.onPartitionsAssigned(Map.of(partition, 3L), secondCallback);

        verifyNoInteractions(secondCallback);
    }

    @Test
    void emptyAssignmentDoesNotCloseInitialReplay() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(), callback);
        listener.onPartitionsAssigned(Map.of(partition, 2L), callback);

        verify(callback).seekToBeginning(List.of(partition));
    }

    @Test
    void indexesPostCreatedEvent() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_CREATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verify(postSearchService).indexPost("post-1", "u001", "Java role");
    }

    @Test
    void ignoresOtherEventTypes() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_UPDATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verifyNoInteractions(postSearchService);
    }
}
