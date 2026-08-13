package it.chewbecca.youtubeanalytics.assistant.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnalyticsActionCatalog {

    private static final String OPENAPI_RESOURCE = "static/openapi-gpt.yaml";

    private final Map<String, ActionDefinition> definitions;
    private final ArrayNode tools;

    public AnalyticsActionCatalog(ObjectMapper objectMapper) {
        JsonNode openApi = loadOpenApi(objectMapper);
        Map<String, ActionDefinition> loadedDefinitions = new LinkedHashMap<>();
        ArrayNode loadedTools = objectMapper.createArrayNode();

        openApi.path("paths").fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode operation = pathEntry.getValue().path("get");
            if (operation.isMissingNode() || !operation.hasNonNull("operationId")) {
                return;
            }

            String operationId = operation.path("operationId").asText();
            List<ActionParameter> parameters = readParameters(openApi, operation.path("parameters"));
            loadedDefinitions.put(operationId, new ActionDefinition(operationId, path, parameters));
            loadedTools.add(toFunctionTool(objectMapper, operationId, operation, parameters));
        });

        if (loadedDefinitions.isEmpty()) {
            throw new IllegalStateException("Nessuna Action trovata in " + OPENAPI_RESOURCE);
        }
        this.definitions = Map.copyOf(loadedDefinitions);
        this.tools = loadedTools;
    }

    public ActionDefinition require(String operationId) {
        ActionDefinition definition = definitions.get(operationId);
        if (definition == null) {
            throw new IllegalArgumentException("Action analytics non consentita: " + operationId);
        }
        return definition;
    }

    public ArrayNode tools() {
        return tools.deepCopy();
    }

    public int size() {
        return definitions.size();
    }

    private JsonNode loadOpenApi(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(OPENAPI_RESOURCE).getInputStream()) {
            Object yaml = new Yaml().load(input);
            return objectMapper.valueToTree(yaml);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossibile caricare " + OPENAPI_RESOURCE, ex);
        }
    }

    private List<ActionParameter> readParameters(JsonNode openApi, JsonNode parameterNodes) {
        List<ActionParameter> parameters = new ArrayList<>();
        if (!parameterNodes.isArray()) {
            return parameters;
        }
        for (JsonNode rawParameter : parameterNodes) {
            JsonNode parameter = resolveParameter(openApi, rawParameter);
            if (!parameter.hasNonNull("name") || !parameter.hasNonNull("in")) {
                continue;
            }
            String location = parameter.path("in").asText();
            boolean required = "path".equals(location) || parameter.path("required").asBoolean(false);
            JsonNode schema = parameter.path("schema");
            parameters.add(new ActionParameter(
                    parameter.path("name").asText(),
                    location,
                    required,
                    parameter.path("description").asText(""),
                    schema.isObject() ? schema.deepCopy() : null
            ));
        }
        return List.copyOf(parameters);
    }

    private JsonNode resolveParameter(JsonNode openApi, JsonNode parameter) {
        if (!parameter.hasNonNull("$ref")) {
            return parameter;
        }
        String reference = parameter.path("$ref").asText();
        String prefix = "#/components/parameters/";
        if (!reference.startsWith(prefix)) {
            throw new IllegalStateException("Riferimento OpenAPI non supportato: " + reference);
        }
        return openApi.path("components").path("parameters").path(reference.substring(prefix.length()));
    }

    private ObjectNode toFunctionTool(ObjectMapper objectMapper,
                                      String operationId,
                                      JsonNode operation,
                                      List<ActionParameter> parameters) {
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        for (ActionParameter parameter : parameters) {
            ObjectNode schema = parameter.schema() != null && parameter.schema().isObject()
                    ? (ObjectNode) parameter.schema().deepCopy()
                    : objectMapper.createObjectNode().put("type", "string");
            if (!parameter.description().isBlank()) {
                schema.put("description", parameter.description());
            }
            properties.set(parameter.name(), schema);
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }

        ObjectNode parameterSchema = objectMapper.createObjectNode();
        parameterSchema.put("type", "object");
        parameterSchema.set("properties", properties);
        parameterSchema.set("required", required);
        parameterSchema.put("additionalProperties", false);

        String description = operation.path("summary").asText(operationId);
        if (operation.hasNonNull("description")) {
            description += ". " + operation.path("description").asText();
        }

        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.put("name", operationId);
        tool.put("description", description);
        tool.set("parameters", parameterSchema);
        tool.put("strict", false);
        return tool;
    }

    public record ActionDefinition(String operationId, String path, List<ActionParameter> parameters) {
    }

    public record ActionParameter(
            String name,
            String location,
            boolean required,
            String description,
            JsonNode schema
    ) {
    }
}
