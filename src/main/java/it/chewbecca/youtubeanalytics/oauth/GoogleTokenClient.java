package it.chewbecca.youtubeanalytics.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.config.GoogleOAuthProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class GoogleTokenClient {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final RestClient restClient;
    private final GoogleOAuthProperties properties;

    public GoogleTokenClient(RestClient googleRestClient, GoogleOAuthProperties properties) {
        this.restClient = googleRestClient;
        this.properties = properties;
    }

    public TokenResponse exchangeAuthorizationCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri().toString());
        form.add("grant_type", "authorization_code");
        return post(form);
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "refresh_token");
        return post(form);
    }

    private TokenResponse post(MultiValueMap<String, String> form) {
        TokenResponse response = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> status.isError(), (request, httpResponse) -> {
                    String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new GoogleApiException(httpResponse.getStatusCode().value(), "Google OAuth token endpoint", body);
                })
                .body(TokenResponse.class);
        if (response == null || !StringUtils.hasText(response.accessToken())) {
            throw new IllegalStateException("Google OAuth non ha restituito un access token valido");
        }
        return response;
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope,
            @JsonProperty("token_type") String tokenType
    ) {
        public Instant expiresAt() {
            return Instant.now().plusSeconds(Math.max(0, expiresIn));
        }
    }
}
