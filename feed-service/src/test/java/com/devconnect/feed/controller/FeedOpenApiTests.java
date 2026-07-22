package com.devconnect.feed.controller;

import com.datastax.oss.driver.api.core.CqlSession;
import com.devconnect.feed.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cassandra.schema-action=none",
        "eureka.client.enabled=false",
        "app.cache.enabled=false",
        "app.openapi.server-url=https://gateway.test"
})
@AutoConfigureMockMvc
class FeedOpenApiTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedService feedService;

    @MockitoBean
    private CqlSession cqlSession;

    @Test
    void publishesFeedPathsInOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/feed/posts']").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts/{postId}']").exists())
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value("https://gateway.test"));
    }

    @Test
    void documentsCursorPagingParametersAlongsideExistingFeedOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/feed/posts'].get").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts/{postId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts'].get.parameters[?(@.name == 'pageNum')]").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts'].get.parameters[?(@.name == 'pageSize')]").exists())
                .andExpect(jsonPath("$.paths['/api/feed/posts'].get.parameters[?(@.name == 'pageToken')]").exists());
    }
}
