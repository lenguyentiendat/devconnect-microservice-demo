package com.devconnect.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI userServiceOpenApi(@Value("${app.openapi.server-url}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("DevConnect User Service API")
                        .version("1.0.0")
                        .description("User creation, update, and internal status APIs."))
                .addServersItem(new Server().url(serverUrl));
    }
}
