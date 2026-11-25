package it.chewbecca.youtubeanalytics.assistant.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionCatalog.ActionDefinition;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionCatalog.ActionParameter;
import it.chewbecca.youtubeanalytics.assistant.config.AssistantProperties;
import it.chewbecca.youtubeanalytics.config.AppSecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class AnalyticsActionExecutor {

    private static final int MAX_TOOL_OUTPUT_CHARACTERS = 120_000;
    private static final java.util.regex.Pattern SAFE_PATH_VALUE =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    private final AnalyticsActionCatalog catalog;
    private final ObjectMapper objectMapper;
    private final AppSecurityProperties securityProperties;
    private final RestClient restClient;
    private final String baseUrl;

    public AnalyticsActionExecutor(AnalyticsActionCatalog catalog,
                                   ObjectMapper objectMapper,
                                   AppSecurityProperties securityProperties,
                                   AssistantProperties assistantProperties,
                                   RestClient.Builder restClientBuilder,
                                   @Value("${server.port}") int serverPort) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
        this.restClient = restClientBuilder.build();
        this.baseUrl = StringUtils.hasText(assistantProperties.actionsBaseUrl())
                ? assistantProperties.actionsBaseUrl().replaceAll("/+$", "")
                : "http://127.0.0.1:" + serverPort;
    }

    public String execute(String operationId, String jsonArguments) {
        ActionDefinition definition = catalog.require(operationId);
        ObjectNode arguments = parseArguments(jsonArguments);
        String path = definition.path();
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl);

        for (ActionParameter parameter : definition.parameters()) {
            JsonNode value = arguments.get(parameter.name());
            if ((value == null || value.isNull() || value.asText().isBlank()) && parameter.required()) {
                return error("Parametro obbligatorio mancante: " + parameter.name());
            }
            if (value == null || value.isNull() || value.asText().isBlank()) {
                continue;
            }
            if ("path".equals(parameter.location())) {
                if (!SAFE_PATH_VALUE.matcher(value.asText()).matches()) {
                    return error("Valore non valido per il parametro path: " + parameter.name());
                }
                path = path.replace("{" + parameter.name() + "}", value.asText());
            } else if ("query".equals(parameter.location())) {
                if (value.isArray()) {
                    value.forEach(item -> uriBuilder.queryParam(parameter.name(), item.asText()));
                } else {
                    uriBuilder.queryParam(parameter.name(), value.asText());
                }
            }
        }

        URI uri = uriBuilder.path(path).build().encode().toUri();
        try {
            String body = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + securityProperties.gptApiKey())
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return "{}";
            }
            return truncate(body);
        } catch (RestClientResponseException ex) {
            return upstreamError(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            return error("L'API analytics non è temporaneamente raggiungibile");
        }
    }

    private ObjectNode parseArguments(String jsonArguments) {
        try {
            JsonNode parsed = objectMapper.readTree(jsonArguments == null ? "{}" : jsonArguments);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("Gli argomenti della Action devono essere un oggetto JSON");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Argomenti Action non validi", ex);
        }
    }

    private String upstreamError(int status, String responseBody) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("actionError", true);
        error.put("status", status);
        error.put("message", "L'API analytics ha rifiutato la richiesta");
        if (StringUtils.hasText(responseBody)) {
            error.put("details", truncate(responseBody));
        }
        return error.toString();
    }

    private String error(String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("actionError", true);
        error.put("message", message);
        return error.toString();
    }

    private String truncate(String value) {
        if (value.length() <= MAX_TOOL_OUTPUT_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_TOOL_OUTPUT_CHARACTERS)
                + "\n[risposta troncata dal backend per limite di sicurezza]";
    }
}
