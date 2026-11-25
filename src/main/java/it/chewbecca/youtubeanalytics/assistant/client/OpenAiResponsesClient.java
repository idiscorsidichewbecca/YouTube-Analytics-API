package it.chewbecca.youtubeanalytics.assistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.chewbecca.youtubeanalytics.assistant.config.AssistantProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiResponsesClient {

    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiResponsesClient(AssistantProperties properties,
                                 ObjectMapper objectMapper,
                                 RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl("https://api.openai.com/v1").build();
    }

    public JsonNode createResponse(String instructions,
                                   JsonNode input,
                                   String previousResponseId,
                                   ArrayNode tools) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", properties.model());
        request.put("instructions", instructions);
        request.set("input", input);
        request.set("tools", tools);
        request.put("store", true);
        request.put("parallel_tool_calls", true);
        request.put("max_output_tokens", properties.maxOutputTokens());
        if (StringUtils.hasText(previousResponseId)) {
            request.put("previous_response_id", previousResponseId);
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.hasNonNull("id")) {
                throw new OpenAiApiException(502, "OpenAI ha restituito una risposta vuota");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new OpenAiApiException(
                    ex.getStatusCode().value(),
                    safeUpstreamMessage(ex.getStatusCode().value())
            );
        } catch (RestClientException ex) {
            throw new OpenAiApiException(502, "OpenAI non raggiungibile");
        }
    }

    private String safeUpstreamMessage(int status) {
        return switch (status) {
            case 401, 403 -> "Configurazione OpenAI non autorizzata";
            case 429 -> "Limite OpenAI temporaneamente raggiunto";
            default -> "Errore OpenAI (HTTP " + status + ")";
        };
    }

    public static class OpenAiApiException extends RuntimeException {
        private final int status;

        public OpenAiApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        public boolean invalidPreviousResponse() {
            return status == 400 || status == 404;
        }
    }
}
