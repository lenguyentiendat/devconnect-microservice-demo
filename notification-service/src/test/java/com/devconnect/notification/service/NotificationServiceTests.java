package com.devconnect.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTests {

    private final NotificationService service = new NotificationService();

    @Test
    void duplicateEventIdCreatesOneNotification() {
        service.createPostCreatedNotification("event-1", "u001", "post-1");
        service.createPostCreatedNotification("event-1", "u001", "post-1");

        assertThat(service.getNotificationsByUser("u001")).hasSize(1);
    }

    @Test
    void notificationsAreFilteredByUser() {
        service.createPostCreatedNotification("event-1", "u001", "post-1");
        service.createPostCreatedNotification("event-2", "u002", "post-2");

        assertThat(service.getNotificationsByUser("u001"))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.userId()).isEqualTo("u001");
                    assertThat(notification.message()).contains("post-1");
                });
    }
}
