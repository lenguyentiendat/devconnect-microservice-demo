package com.devconnect.search.controller;

import com.devconnect.search.dto.ApiResponse;
import com.devconnect.search.dto.SearchPostResponse;
import com.devconnect.search.service.PostSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "Search indexed post content")
public class SearchController {

    private final PostSearchService postSearchService;

    public SearchController(PostSearchService postSearchService) {
        this.postSearchService = postSearchService;
    }

    @GetMapping("/posts")
    @Operation(summary = "Search posts", description = "Search indexed posts by a keyword.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Search result returned")
    public ApiResponse<List<SearchPostResponse>> searchPosts(
            @Parameter(description = "Text to search for", example = "Kafka", required = true)
            @RequestParam String keyword
    ) {
        return ApiResponse.success("Search result", postSearchService.search(keyword));
    }
}
