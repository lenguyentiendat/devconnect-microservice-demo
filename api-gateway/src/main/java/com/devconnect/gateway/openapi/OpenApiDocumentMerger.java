package com.devconnect.gateway.openapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpenApiDocumentMerger {

    private static final Pattern COMPONENT_REFERENCE =
            Pattern.compile("^#/components/([^/]+)/(.+)$");
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace"
    );

    public ObjectNode merge(List<ServiceDocument> documents, String gatewayUrl) {
        if (documents.isEmpty()) {
            throw new IllegalStateException("No service OpenAPI documents available");
        }

        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        merged.put("openapi", documents.getFirst().document().path("openapi").asText("3.0.1"));
        merged.set("info", info(gatewayUrl));
        ArrayNode servers = merged.putArray("servers");
        servers.addObject().put("url", gatewayUrl);

        ObjectNode paths = merged.putObject("paths");
        ObjectNode components = merged.putObject("components");
        ArrayNode tags = merged.putArray("tags");
        Set<String> tagNames = new HashSet<>();

        for (ServiceDocument service : documents) {
            mergePaths(paths, service);
            mergeComponents(components, service);
            mergeTags(tags, tagNames, service.document().path("tags"));
        }

        return merged;
    }

    private ObjectNode info(String gatewayUrl) {
        return JsonNodeFactory.instance.objectNode()
                .put("title", "DevConnect Aggregated API")
                .put("version", "1.0.0")
                .put("description", "Aggregated OpenAPI document served by the API Gateway at " + gatewayUrl);
    }

    private void mergePaths(ObjectNode target, ServiceDocument service) {
        JsonNode sourcePaths = service.document().path("paths");
        sourcePaths.properties().forEach(pathEntry -> {
            String path = pathEntry.getKey();
            ObjectNode targetPath = target.has(path)
                    ? (ObjectNode) target.get(path)
                    : target.putObject(path);
            pathEntry.getValue().properties().forEach(operationEntry -> {
                String method = operationEntry.getKey();
                if (!HTTP_METHODS.contains(method.toLowerCase())) {
                    return;
                }
                if (targetPath.has(method)) {
                    throw new IllegalStateException("Duplicate OpenAPI operation: " + method + " " + path);
                }
                targetPath.set(method, rewrite(operationEntry.getValue(), service.prefix(), true));
            });
        });
    }

    private void mergeComponents(ObjectNode target, ServiceDocument service) {
        JsonNode sourceComponents = service.document().path("components");
        sourceComponents.properties().forEach(sectionEntry -> {
            String section = sectionEntry.getKey();
            ObjectNode targetSection = target.withObject(section);
            sectionEntry.getValue().properties().forEach(componentEntry -> {
                String componentName = service.prefix() + "_" + componentEntry.getKey();
                if (targetSection.has(componentName)) {
                    throw new IllegalStateException("Duplicate OpenAPI component: " + componentName);
                }
                targetSection.set(componentName, rewrite(componentEntry.getValue(), service.prefix(), false));
            });
        });
    }

    private void mergeTags(ArrayNode target, Set<String> existingNames, JsonNode sourceTags) {
        if (!sourceTags.isArray()) {
            return;
        }
        sourceTags.forEach(tag -> {
            String name = tag.path("name").asText();
            if (existingNames.add(name)) {
                target.add(tag);
            }
        });
    }

    private JsonNode rewrite(JsonNode source, String prefix, boolean rewriteOperationId) {
        if (source.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            source.properties().forEach(entry -> {
                String fieldName = entry.getKey();
                JsonNode value = entry.getValue();
                if ("$ref".equals(fieldName) && value.isTextual()) {
                    copy.put(fieldName, rewriteReference(value.asText(), prefix));
                } else if (rewriteOperationId && "operationId".equals(fieldName) && value.isTextual()) {
                    copy.put(fieldName, prefix + "_" + value.asText());
                } else {
                    copy.set(fieldName, rewrite(value, prefix, rewriteOperationId));
                }
            });
            return copy;
        }
        if (source.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            source.forEach(value -> copy.add(rewrite(value, prefix, rewriteOperationId)));
            return copy;
        }
        return source;
    }

    private String rewriteReference(String reference, String prefix) {
        Matcher matcher = COMPONENT_REFERENCE.matcher(reference);
        if (!matcher.matches()) {
            return reference;
        }
        return "#/components/" + matcher.group(1) + "/" + prefix + "_" + matcher.group(2);
    }

    public record ServiceDocument(String prefix, JsonNode document) {
    }
}
