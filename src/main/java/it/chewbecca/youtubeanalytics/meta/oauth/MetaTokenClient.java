package it.chewbecca.youtubeanalytics.meta.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphApiException;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetaTokenClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MetaProperties properties;

    public MetaTokenClient(RestClient googleRestClient,
                           ObjectMapper objectMapper,
                           MetaProperties properties) {
        this.restClient = googleRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public TokenResponse exchangeAuthorizationCode(String code) {
        properties.requireConfigured();
        URI uri = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + properties.normalizedGraphApiVersion() + "/oauth/access_token")
                .queryParam("client_id", properties.appId())
                .queryParam("client_secret", properties.appSecret())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("code", code)
                .build()
                .encode()
                .toUri();

        JsonNode root = executeJsonGet(uri, null, "oauth/access_token",
                "Meta non ha accettato l'authorization code");

        // Facebook Login for Business può restituire il token direttamente alla
        // radice oppure dentro l'oggetto "data", in base alla variante configurata.
        JsonNode tokenNode = root.path("data").isObject() ? root.path("data") : root;
        String accessToken = tokenNode.path("access_token").asText(null);
        String tokenType = tokenNode.path("token_type").asText(null);
        Long expiresIn = tokenNode.path("expires_in").canConvertToLong()
                ? tokenNode.path("expires_in").asLong()
                : null;

        if (!StringUtils.hasText(accessToken)) {
            throw new MetaGraphApiException(
                    200,
                    null,
                    null,
                    "oauth/access_token",
                    root.toString(),
                    "Meta OAuth ha restituito una risposta senza access token"
            );
        }
        return new TokenResponse(accessToken, tokenType, expiresIn);
    }

    public DebugToken debug(String inputToken) {
        URI uri = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + properties.normalizedGraphApiVersion() + "/debug_token")
                .queryParam("input_token", inputToken)
                .build()
                .encode()
                .toUri();

        JsonNode root = executeJsonGet(
                uri,
                "Bearer " + properties.appId() + "|" + properties.appSecret(),
                "debug_token",
                "Impossibile verificare il token Meta"
        );

        JsonNode data = root.path("data");
        if (data.isMissingNode() || !data.path("is_valid").asBoolean(false)) {
            throw new IllegalStateException("Meta ha restituito un token non valido");
        }
        List<String> scopes = new ArrayList<>();
        data.path("scopes").forEach(scope -> scopes.add(scope.asText()));

        Map<String, List<String>> granularScopes = new LinkedHashMap<>();
        data.path("granular_scopes").forEach(granularScope -> {
            String scope = granularScope.path("scope").asText(null);
            if (!StringUtils.hasText(scope)) {
                return;
            }
            List<String> targetIds = new ArrayList<>();
            granularScope.path("target_ids").forEach(targetId -> {
                String value = targetId.asText(null);
                if (StringUtils.hasText(value)) {
                    targetIds.add(value);
                }
            });
            granularScopes.put(scope, List.copyOf(targetIds));
        });

        long expiresAt = data.path("expires_at").asLong(0);
        return new DebugToken(
                data.path("app_id").asText(null),
                data.path("type").asText(null),
                data.path("user_id").asText(null),
                List.copyOf(scopes),
                Map.copyOf(granularScopes),
                expiresAt > 0 ? Instant.ofEpochSecond(expiresAt) : null,
                data.path("data_access_expires_at").asLong(0) > 0
                        ? Instant.ofEpochSecond(data.path("data_access_expires_at").asLong())
                        : null
        );
    }

    /**
     * Meta può restituire JSON dichiarandolo come text/javascript. Leggere prima il
     * corpo come byte evita di dipendere dall'HttpMessageConverter associato al
     * Content-Type; il parsing JSON viene poi eseguito esplicitamente con Jackson.
     */
    private JsonNode executeJsonGet(URI uri,
                                    String authorization,
                                    String operation,
                                    String errorMessage) {
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
        if (StringUtils.hasText(authorization)) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        byte[] responseBytes = request
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new MetaGraphApiException(
                            httpResponse.getStatusCode().value(),
                            null,
                            null,
                            operation,
                            body,
                            errorMessage
                    );
                })
                .body(byte[].class);

        String responseBody = responseBytes == null
                ? ""
                : new String(responseBytes, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(responseBody)) {
            throw new MetaGraphApiException(
                    200,
                    null,
                    null,
                    operation,
                    responseBody,
                    "Meta ha restituito una risposta vuota durante " + operation
            );
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
                    "Meta ha restituito una risposta non JSON durante " + operation
            );
        }
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
        public Instant fallbackExpiresAt(MetaProperties properties) {
            if (expiresIn != null && expiresIn > 0) {
                return Instant.now().plusSeconds(expiresIn);
            }
            return Instant.now().plus(properties.tokenExpiryFallback());
        }
    }

    public record DebugToken(
            String appId,
            String type,
            String userId,
            List<String> scopes,
            Map<String, List<String>> granularScopes,
            Instant expiresAt,
            Instant dataAccessExpiresAt
    ) {
        public List<String> targetIds(String scope) {
            return granularScopes == null ? List.of() : granularScopes.getOrDefault(scope, List.of());
        }
    }
}
