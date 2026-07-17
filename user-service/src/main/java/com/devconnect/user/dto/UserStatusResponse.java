package com.devconnect.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserStatusResponse", description = "Lightweight internal status response; email is intentionally omitted")
public record UserStatusResponse(
        @Schema(example = "u001")
        String userId,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
        String status
) {

}
