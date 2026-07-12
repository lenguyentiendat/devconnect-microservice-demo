package com.devconnect.search.controller;

import com.devconnect.search.dto.ApiResponse;
import com.devconnect.search.dto.SearchPostResponse;
import com.devconnect.search.service.PostSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final PostSearchService postSearchService;

    public SearchController(PostSearchService postSearchService) {
        this.postSearchService = postSearchService;
    }

    @GetMapping("/posts")
    public ApiResponse<List<SearchPostResponse>> searchPosts(@RequestParam String keyword) {
        return ApiResponse.success("Search result", postSearchService.search(keyword));
    }
}
