package com.devconnect.search.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostSearchServiceTests {

    private final PostSearchService service = new PostSearchService();

    @Test
    void searchIsCaseInsensitive() {
        service.indexPost("post-1", "u001", "Java Developer");

        assertThat(service.search("java"))
                .singleElement()
                .satisfies(post -> assertThat(post.postId()).isEqualTo("post-1"));
    }

    @Test
    void replayUpsertsTheSamePostId() {
        service.indexPost("post-1", "u001", "Old content");
        service.indexPost("post-1", "u001", "New content");

        assertThat(service.search(""))
                .singleElement()
                .satisfies(post -> assertThat(post.content()).isEqualTo("New content"));
    }
}
