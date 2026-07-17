package com.devconnect.notification.controller;

import com.devconnect.notification.dto.ApiResponse;
import com.devconnect.notification.dto.NotificationResponse;
import com.devconnect.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "Read notifications generated from post events")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "List user notifications", description = "Return notifications currently available for a user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notifications found")
    public ApiResponse<List<NotificationResponse>> getNotificationsByUser(
            @Parameter(description = "User identifier", example = "u001", required = true)
            @PathVariable String userId
    ) {
        return ApiResponse.success("Notifications found", notificationService.getNotificationsByUser(userId));
    }
}
