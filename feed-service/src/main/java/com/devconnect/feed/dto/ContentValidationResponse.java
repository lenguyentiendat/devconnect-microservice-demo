package com.devconnect.feed.dto;

public record ContentValidationResponse(
        boolean allowed,
        String reason
) {
}
