package com.devconnect.gateway.openapi;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiDocumentMergerTests {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final OpenApiDocumentMerger merger = new OpenApiDocumentMerger();

    @Test
    void mergesServicePathsAndNamespacesOperationsAndComponents() throws Exception {
        ObjectNode user = objectMapper.readValue("""
                {
                  "openapi":"3.0.3",
                  "paths":{
                    "/api/users":{
                      "post":{
                        "operationId":"createUser",
                        "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/ApiResponse"}}}}}
                      }
                    }
                  },
                  "components":{"schemas":{"ApiResponse":{"type":"object"}}}
                }
                """, ObjectNode.class);
        ObjectNode feed = objectMapper.readValue("""
                {
                  "openapi":"3.0.3",
                  "paths":{
                    "/api/feed/posts":{
                      "get":{
                        "operationId":"getPosts",
                        "responses":{"200":{"description":"ok"}}
                      }
                    }
                  },
                  "components":{"schemas":{"ApiResponse":{"type":"object"}}}
                }
                """, ObjectNode.class);

        ObjectNode merged = merger.merge(List.of(
                new OpenApiDocumentMerger.ServiceDocument("userService", user),
                new OpenApiDocumentMerger.ServiceDocument("feedService", feed)
        ), "http://localhost:8090");

        assertTrue(merged.path("paths").has("/api/users"));
        assertTrue(merged.path("paths").has("/api/feed/posts"));
        assertEquals("userService_createUser",
                merged.at("/paths/~1api~1users/post/operationId").asText());
        assertEquals("#/components/schemas/userService_ApiResponse",
                merged.at("/paths/~1api~1users/post/responses/200/content/application~1json/schema/$ref").asText());
        assertTrue(merged.path("components").path("schemas").has("userService_ApiResponse"));
        assertTrue(merged.path("components").path("schemas").has("feedService_ApiResponse"));
        assertFalse(merged.path("components").path("schemas").has("ApiResponse"));
        assertEquals("http://localhost:8090", merged.path("servers").get(0).path("url").asText());
    }
}
