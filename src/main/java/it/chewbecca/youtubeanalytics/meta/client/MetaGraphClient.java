package it.chewbecca.youtubeanalytics.meta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.meta.exception.MetaTokenExpiredException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetaGraphClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MetaProperties properties;

    public MetaGraphClient(RestClient googleRestClient, ObjectMapper objectMapper, MetaProperties properties) {
        this.restClient = googleRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public JsonNode get(String path, String accessToken, Map<String, ?> parameters, String operation) {
        URI uri = buildUri(path, accessToken, parameters);
        return executeGet(uri, accessToken, operation);
    }

    public PagedResult getPage(String path,
                               String accessToken,
                               Map<String, ?> parameters,
                               int limit,
                               String after,
                               String operation) {
        Map<String, Object> query = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        query.put("limit", Math.max(1, Math.min(100, limit)));
        if (StringUtils.hasText(after)) {
            query.put("after", after);
        }
        JsonNode root = get(path, accessToken, query, operation);
        List<JsonNode> data = new ArrayList<>();
        root.path("data").forEach(data::add);
        return new PagedResult(data, textOrNull(root.path("paging").path("cursors").path("after")));
    }

    public List<JsonNode> getAll(String path,
                                 String accessToken,
                                 Map<String, ?> parameters,
                                 int maxItems,
                                 String operation) {
        if (maxItems <= 0) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        String after = null;
        int calls = 0;
        do {
            PagedResult page = getPage(
                    path,
                    accessToken,
                    parameters,
                    Math.min(100, maxItems - result.size()),
                    after,
                    operation
            );
            result.addAll(page.data());
            after = page.nextCursor();
            calls++;
        } while (StringUtils.hasText(after) && result.size() < maxItems && calls < 100);
        return result.size() <= maxItems ? List.copyOf(result) : List.copyOf(result.subList(0, maxItems));
    }

    private URI buildUri(String path, String accessToken, Map<String, ?> parameters) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + properties.normalizedGraphApiVersion() + normalizedPath);
        if (parameters != null) {
            parameters.forEach((name, value) -> {
                if (value != null && StringUtils.hasText(value.toString())) {
                    builder.queryParam(name, value);
                }
            });
        }
        if (StringUtils.hasText(properties.appSecret()) && StringUtils.hasText(accessToken)) {
            builder.queryParam("appsecret_proof", appSecretProof(accessToken));
        }
        return builder.build().encode().toUri();
    }

    private JsonNode executeGet(URI uri, String accessToken, String operation) {
        // Alcuni endpoint Meta dichiarano il JSON come text/javascript. Leggiamo il
        // corpo come byte e lo deserializziamo esplicitamente per non dipendere dal
        // Content-Type scelto da Meta.
        byte[] responseBytes = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (request, httpResponse) -> {
                    String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw toException(httpResponse.getStatusCode().value(), operation, body);
                })
                .body(byte[].class);

        String responseBody = responseBytes == null
                ? ""
                : new String(responseBytes, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("Meta Graph API non ha restituito una risposta per " + operation);
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new MetaGraphApiException(
                    200,
                    null,
                    null,
                    operation,
                    responseBody,
                    "Meta Graph API ha restituito una risposta non JSON durante " + operation
            );
        }
    }

    private RuntimeException toException(int statusCode, String operation, String body) {
        Integer graphCode = null;
        Integer graphSubcode = null;
        String graphMessage = null;
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            if (error.has("code")) {
                graphCode = error.path("code").asInt();
            }
            if (error.has("error_subcode")) {
                graphSubcode = error.path("error_subcode").asInt();
            }
            graphMessage = textOrNull(error.path("message"));
        } catch (Exception ignored) {
            // Il corpo originale resta disponibile nell'eccezione.
        }
        if (graphCode != null && graphCode == 190) {
            return new MetaTokenExpiredException(
                    "Il token Meta non è più valido (code 190). Ripeti il collegamento da /admin/meta/connect."
            );
        }
        String message = "Errore Meta Graph API durante " + operation
                + (graphMessage == null ? "" : ": " + graphMessage);
        return new MetaGraphApiException(statusCode, graphCode, graphSubcode, operation, body, message);
    }

    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Impossibile calcolare appsecret_proof per Meta", ex);
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())
                ? null
                : node.asText();
    }

    public record PagedResult(List<JsonNode> data, String nextCursor) {
    }
}
