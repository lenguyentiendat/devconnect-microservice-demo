package com.devconnect.user.dto;

import com.devconnect.user.support.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateUserRequest", description = "Payload used to create a user")
public record CreateUserRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 64, message = "userId must not exceed 64 characters")
        @Schema(description = "Stable user identifier", example = "u004", maxLength = 64)
        String userId,
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        @Schema(description = "Initial user status", example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
        String status,
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 254, message = "email must not exceed 254 characters")
        @Schema(description = "Unique email; trimmed and normalized to lowercase", example = "user@example.com", maxLength = 254)
        String email
) {
    public CreateUserRequest {
        email = EmailNormalizer.normalize(email);
    }
}
