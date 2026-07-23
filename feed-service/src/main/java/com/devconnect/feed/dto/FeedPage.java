package com.devconnect.feed.dto;

import java.util.List;

public record FeedPage(List<PostResponse> items, boolean hasNext) {
}
