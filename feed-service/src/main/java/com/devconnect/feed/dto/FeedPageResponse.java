package com.devconnect.feed.dto;

import java.util.List;
import java.time.LocalDateTime;

public record FeedPageResponse(
        List<PostResponse> items,
        int pageSize,
        boolean hasNext,
        LocalDateTime nextLastCreatedAt,
        String nextLastPostId,
        long feedRevision
) {
}
