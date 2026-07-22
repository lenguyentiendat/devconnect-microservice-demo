package com.devconnect.gateway.openapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.List;

@RestController
public class OpenApiAggregationController {

    private static final List<RemoteApi> REMOTE_APIS = List.of(
            new RemoteApi("user-service", "userService"),
            new RemoteApi("feed-service", "feedService"),
            new RemoteApi("search-service", "searchService"),
            new RemoteApi("notification-service", "notificationService")
    );

    private final WebClient.Builder webClientBuilder;
    private final OpenApiDocumentMerger merger;
    private final String gatewayUrl;

    public OpenApiAggregationController(
            WebClient.Builder webClientBuilder,
            OpenApiDocumentMerger merger,
            @Value("${app.openapi.server-url:http://localhost:8090}") String gatewayUrl
    ) {
        this.webClientBuilder = webClientBuilder;
        this.merger = merger;
        this.gatewayUrl = gatewayUrl;
    }

    @GetMapping(value = "/v3/api-docs/aggregate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> getAggregatedDocument() {
        return Flux.fromIterable(REMOTE_APIS)
                .concatMap(this::fetchDocument)
                .collectList()
                .map(documents -> merger.merge(documents, gatewayUrl));
    }

    private Mono<OpenApiDocumentMerger.ServiceDocument> fetchDocument(RemoteApi remoteApi) {
        return webClientBuilder.build()
                .get()
                .uri("http://" + remoteApi.serviceName() + "/v3/api-docs")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(document -> new OpenApiDocumentMerger.ServiceDocument(remoteApi.prefix(), document));
    }

    private record RemoteApi(String serviceName, String prefix) {
    }
}
