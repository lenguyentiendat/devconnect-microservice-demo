package com.devconnect.notification.controller;

import com.devconnect.notification.dto.ApiResponse;
import com.devconnect.notification.dto.NotificationResponse;
import com.devconnect.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<List<NotificationResponse>> getNotificationsByUser(@PathVariable String userId) {
        return ApiResponse.success("Notifications found", notificationService.getNotificationsByUser(userId));
    }
}
