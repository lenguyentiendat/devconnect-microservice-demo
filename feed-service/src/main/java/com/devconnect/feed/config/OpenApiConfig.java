package com.devconnect.feed.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI feedServiceOpenApi(@Value("${app.openapi.server-url}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("DevConnect Feed Service API")
                        .version("1.0.0")
                        .description("Create and read feed posts with author validation."))
                .addServersItem(new Server().url(serverUrl));
    }
}
