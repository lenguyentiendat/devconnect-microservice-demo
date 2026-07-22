package com.devconnect.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false"
        }
)
@AutoConfigureWebTestClient
class ApiGatewayApplicationTests {

    private static final String ORIGIN = "http://localhost:8090";

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void declaresExactLoadBalancedBusinessAndOpenApiRoutes() {
        Map<String, RouteDefinition> routes = routesById();
        assertEquals(9, routes.size());
        assertRoute(routes, "user-service", "lb://user-service", "/api/users,/api/users/**");
        assertRoute(routes, "user-service-internal", "lb://user-service", "/internal/users/**");
        assertRoute(routes, "feed-service", "lb://feed-service", "/api/feed/posts,/api/feed/posts/**");
        assertRoute(routes, "search-service", "lb://search-service", "/api/search,/api/search/**");
        assertRoute(routes, "notification-service", "lb://notification-service", "/api/notifications,/api/notifications/**");
        assertOpenApiRoute(routes, "user-service-openapi", "lb://user-service", "/openapi/user/v3/api-docs");
        assertOpenApiRoute(routes, "feed-service-openapi", "lb://feed-service", "/openapi/feed/v3/api-docs");
        assertOpenApiRoute(routes, "search-service-openapi", "lb://search-service", "/openapi/search/v3/api-docs");
        assertOpenApiRoute(routes, "notification-service-openapi", "lb://notification-service", "/openapi/notification/v3/api-docs");
    }

    @Test
    void answersPreflightForEveryBusinessRoute() {
        List.of(
                "/api/users",
                "/api/feed/posts",
                "/api/search/posts",
                "/api/notifications/users/u001"
        ).forEach(this::assertSuccessfulPreflight);
    }

    @Test
    void publishesSingleAggregatedSwaggerDocument() {
        webTestClient.get()
                .uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("/v3/api-docs/aggregate")
                .jsonPath("$.urls").doesNotExist();
    }

    @Test
    void doesNotRouteUnknownPaths() {
        List.of("/actuator/downstream", "/unknown")
                .forEach(path -> webTestClient.get().uri(path).exchange().expectStatus().isNotFound());
    }

    private Map<String, RouteDefinition> routesById() {
        return routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .blockOptional()
                .orElseThrow()
                .stream()
                .collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
    }

    private void assertRoute(Map<String, RouteDefinition> routes, String id, String uri, String paths) {
        RouteDefinition route = routes.get(id);
        assertEquals(uri, route.getUri().toString());
        assertEquals(paths, String.join(",", route.getPredicates().getFirst().getArgs().values()));
        assertTrue(route.getFilters().isEmpty());
    }

    private void assertOpenApiRoute(Map<String, RouteDefinition> routes, String id, String uri, String path) {
        RouteDefinition route = routes.get(id);
        assertEquals(uri, route.getUri().toString());
        assertEquals(path, String.join(",", route.getPredicates().getFirst().getArgs().values()));
        assertTrue(route.getFilters().toString().contains("/v3/api-docs"));
    }

    private void assertSuccessfulPreflight(String path) {
        webTestClient.method(HttpMethod.OPTIONS)
                .uri(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
    }
}
