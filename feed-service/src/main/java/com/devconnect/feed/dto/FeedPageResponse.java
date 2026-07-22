package com.devconnect.feed.dto;

import java.util.List;

public record FeedPageResponse(
        List<PostResponse> items,
        int pageNum,
        int pageSize,
        boolean hasNext,
        String nextPageToken,
        long feedRevision
) {
}
