package com.devconnect.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI notificationServiceOpenApi(@Value("${app.openapi.server-url}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("DevConnect Notification Service API")
                        .version("1.0.0")
                        .description("Read notifications generated from post events."))
                .addServersItem(new Server().url(serverUrl));
    }
}
