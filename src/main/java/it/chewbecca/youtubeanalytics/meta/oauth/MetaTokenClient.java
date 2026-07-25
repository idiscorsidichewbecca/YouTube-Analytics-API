package it.chewbecca.youtubeanalytics.meta.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

@Component
public class MetaTokenClient {

    private final RestClient restClient;
    private final MetaProperties properties;

    public MetaTokenClient(RestClient googleRestClient, MetaProperties properties) {
        this.restClient = googleRestClient;
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

        JsonNode root = restClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.isError(), (request, httpResponse) -> {
                    String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new MetaGraphApiException(
                            httpResponse.getStatusCode().value(),
                            null,
                            null,
                            "oauth/access_token",
                            body,
                            "Meta non ha accettato l'authorization code"
                    );
                })
                .body(JsonNode.class);

        // Facebook Login for Business con System User token può restituire il token
        // direttamente alla radice oppure dentro l'oggetto "data". Supportiamo entrambi
        // i formati per non dipendere dalla variante di login configurata su Meta.
        JsonNode tokenNode = root != null && root.path("data").isObject()
                ? root.path("data")
                : root;
        String accessToken = tokenNode == null ? null : tokenNode.path("access_token").asText(null);
        String tokenType = tokenNode == null ? null : tokenNode.path("token_type").asText(null);
        Long expiresIn = tokenNode != null && tokenNode.path("expires_in").canConvertToLong()
                ? tokenNode.path("expires_in").asLong()
                : null;

        if (!StringUtils.hasText(accessToken)) {
            String body = root == null ? "null" : root.toString();
            throw new MetaGraphApiException(
                    200,
                    null,
                    null,
                    "oauth/access_token",
                    body,
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
        JsonNode root = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.appId() + "|" + properties.appSecret())
                .retrieve()
                .onStatus(status -> status.isError(), (request, httpResponse) -> {
                    String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new MetaGraphApiException(
                            httpResponse.getStatusCode().value(),
                            null,
                            null,
                            "debug_token",
                            body,
                            "Impossibile verificare il token Meta"
                    );
                })
                .body(JsonNode.class);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || data.isMissingNode() || !data.path("is_valid").asBoolean(false)) {
            throw new IllegalStateException("Meta ha restituito un token non valido");
        }
        List<String> scopes = new ArrayList<>();
        data.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        long expiresAt = data.path("expires_at").asLong(0);
        return new DebugToken(
                data.path("app_id").asText(null),
                data.path("type").asText(null),
                data.path("user_id").asText(null),
                scopes,
                expiresAt > 0 ? Instant.ofEpochSecond(expiresAt) : null,
                data.path("data_access_expires_at").asLong(0) > 0
                        ? Instant.ofEpochSecond(data.path("data_access_expires_at").asLong())
                        : null
        );
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
            Instant expiresAt,
            Instant dataAccessExpiresAt
    ) {
    }
}
