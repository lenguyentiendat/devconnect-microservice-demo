package com.devconnect.feed.dto;

import java.nio.ByteBuffer;
import java.util.List;

public record FeedPage(List<PostResponse> items, ByteBuffer nextPagingState) {
}
