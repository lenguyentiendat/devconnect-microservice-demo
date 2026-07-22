package com.devconnect.search.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI searchServiceOpenApi(@Value("${app.openapi.server-url}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("DevConnect Search Service API")
                        .version("1.0.0")
                        .description("Search indexed posts by keyword."))
                .addServersItem(new Server().url(serverUrl));
    }
}
