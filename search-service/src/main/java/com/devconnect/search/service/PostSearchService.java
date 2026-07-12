package com.devconnect.search.service;

import com.devconnect.search.dto.SearchPostResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PostSearchService {

    private final Map<String, SearchPostResponse> index = new ConcurrentHashMap<>();

    public void indexPost(String postId, String authorId, String content) {
        index.put(postId, new SearchPostResponse(postId, authorId, content));
    }

    public List<SearchPostResponse> search(String keyword) {
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        return index.values().stream()
                .filter(post -> post.content().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .toList();
    }
}
