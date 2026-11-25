package it.chewbecca.youtubeanalytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class YouTubeStudioClient {
    public static final String DATA = "https://www.googleapis.com/youtube/v3/";
    public static final String REPORTING = "https://youtubereporting.googleapis.com/v1/";
    private final RestClient client;
    private final GoogleAccessTokenService tokens;

    public YouTubeStudioClient(RestClient googleRestClient, GoogleAccessTokenService tokens) {
        this.client = googleRestClient;
        this.tokens = tokens;
    }

    public JsonNode get(String base, String resource, Map<String, ?> parameters) {
        URI uri = uri(base, resource, parameters);
        for (int attempt = 0; ; attempt++) {
            String bearer = tokens.getValidAccessToken();
            try {
                return client.get().uri(uri).headers(h -> h.setBearerAuth(bearer)).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException error) {
                var google = error(resource, error);
                if (attempt > 0 || (google.getStatusCode() < 500 && google.getStatusCode() != 401)) throw google;
                if (google.getStatusCode() == 401) tokens.refreshRejectedToken(bearer);
            } catch (ResourceAccessException error) {
                if (attempt > 0) throw error;
            }
        }
    }

    public JsonNode write(String base, String resource, Map<String, ?> parameters, Object body, boolean put,
                          String etag) {
        String bearer = tokens.getValidAccessToken();
        try {
            var request = (put ? client.put() : client.post()).uri(uri(base, resource, parameters))
                    .headers(h -> {
                        h.setBearerAuth(bearer);
                        if (etag != null && !etag.isBlank()) h.setIfMatch(
                                etag.startsWith("\"") || etag.startsWith("W/") ? etag : "\"" + etag + "\"");
                    }).body(body);
            return request.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                // Refresh for a subsequent explicit retry. The write itself is never repeated here.
                tokens.refreshRejectedToken(bearer);
            }
            throw error(resource, error);
        }
    }

    public static boolean quota(GoogleApiException error) {
        String body = error.getResponseBody();
        return error.getStatusCode() == 429 || (body != null && (body.contains("quotaExceeded")
                || body.contains("rateLimitExceeded") || body.contains("dailyLimitExceeded")
                || body.contains("userRateLimitExceeded")));
    }

    public static boolean uncertain(RuntimeException error) {
        return !(error instanceof GoogleApiException google && google.getStatusCode() >= 400
                && google.getStatusCode() < 500 && google.getStatusCode() != 408);
    }

    private static URI uri(String base, String resource, Map<String, ?> parameters) {
        var builder = UriComponentsBuilder.fromUriString(base).path(resource);
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private static GoogleApiException error(String resource, RestClientResponseException error) {
        return new GoogleApiException(error.getStatusCode().value(), resource, error.getResponseBodyAsString());
    }
}
