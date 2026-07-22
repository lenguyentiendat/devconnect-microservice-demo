package com.devconnect.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OpenApiAggregationConfig {

    @Bean
    @LoadBalanced
    WebClient.Builder openApiWebClientBuilder() {
        return WebClient.builder();
    }
}
