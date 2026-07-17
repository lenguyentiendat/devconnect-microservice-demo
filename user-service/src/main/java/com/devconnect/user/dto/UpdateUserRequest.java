package com.devconnect.user.dto;

import com.devconnect.user.support.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateUserRequest", description = "Payload used to update a user's status and optional email")
public record UpdateUserRequest(
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        @Schema(description = "New user status", example = "INACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
        String status,
        @Pattern(regexp = ".*\\S.*", message = "email must not be blank")
        @Email(message = "email must be valid")
        @Size(max = 254, message = "email must not exceed 254 characters")
        @Schema(description = "Optional replacement email; trimmed and normalized to lowercase", example = "new-address@example.com", maxLength = 254, nullable = true)
        String email
) {
    public UpdateUserRequest {
        email = EmailNormalizer.normalize(email);
    }
}
