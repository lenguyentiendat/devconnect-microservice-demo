package com.devconnect.user.controller;

import com.devconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.openapi.server-url=https://gateway.test")
@AutoConfigureMockMvc
class UserOpenApiTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void publishesUserPathsInOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/users']").exists())
                .andExpect(jsonPath("$.paths['/api/users/{userId}']").exists())
                .andExpect(jsonPath("$.paths['/internal/users/{userId}/status']").exists())
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value("https://gateway.test"));
    }
}
