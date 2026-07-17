package com.devconnect.user.controller;

import com.devconnect.user.dto.ApiResponse;
import com.devconnect.user.dto.CreateUserRequest;
import com.devconnect.user.dto.UpdateUserRequest;
import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.service.UserService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Users", description = "Public user profile writes and internal status lookup")
public class UserInternalController {

    private final UserService userService;

    public UserInternalController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/internal/users/{userId}/status")
    @Operation(
            summary = "Get user status",
            description = "Service-to-service lookup of a user's current status. Email is intentionally not exposed."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User status found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<ApiResponse<UserStatusResponse>> getUserStatus(
            @Parameter(description = "Stable user identifier", example = "u001")
            @PathVariable String userId
    ) {
        return userService.getUserStatus(userId)
                .map(response -> ResponseEntity.ok(
                        ApiResponse.success("User status found", response)
                ))
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("User not found")));
    }

    @PostMapping("/api/users")
    @Operation(summary = "Create user", description = "Create a user with a normalized, unique email address.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User ID or email already exists"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error")
    })
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        UserResponse response = userService.createUser(
                request.userId(), request.status(), request.email()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created successfully", response));
    }

    @PutMapping("/api/users/{userId}")
    @Operation(summary = "Update user", description = "Update status and optionally replace the user's normalized email.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email already exists"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error")
    })
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @Parameter(description = "Stable user identifier", example = "u001")
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UserResponse response = userService.updateUser(
                userId, request.status(), request.email()
        );
        return ResponseEntity.ok(ApiResponse.success("User updated successfully", response));
    }
}
