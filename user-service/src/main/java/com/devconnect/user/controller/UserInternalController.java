package com.devconnect.user.controller;

import com.devconnect.user.dto.ApiResponse;
import com.devconnect.user.dto.CreateUserRequest;
import com.devconnect.user.dto.UpdateUserRequest;
import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserInternalController {

    private final UserService userService;

    public UserInternalController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/internal/users/{userId}/status")
    public ResponseEntity<ApiResponse<UserStatusResponse>> getUserStatus(@PathVariable String userId) {
        return userService.getUserStatus(userId)
                .map(response -> ResponseEntity.ok(
                        ApiResponse.success("User status found", response)
                ))
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("User not found")));
    }

    @PostMapping("/api/users")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        UserResponse response = userService.createUser(request.userId(), request.status());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created successfully", response));
    }

    @PutMapping("/api/users/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UserResponse response = userService.updateUser(userId, request.status());
        return ResponseEntity.ok(ApiResponse.success("User updated successfully", response));
    }
}
