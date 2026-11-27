package it.chewbecca.youtubeanalytics.thumbnail;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class YouTubeThumbnailClient {
    private static final String API = "https://www.googleapis.com/youtube/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/youtube/v3/thumbnails/set";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(YouTubeThumbnailClient.class);
    private final CaptionExcerptCache cache;
    private final RestClient rest;
    private final GoogleAccessTokenService tokens;

    public YouTubeThumbnailClient(RestClient googleRestClient, GoogleAccessTokenService tokens, CaptionExcerptCache cache) {
        this.rest = googleRestClient;
        this.tokens = tokens;
        this.cache = cache;
    }

    /** Single-instance, on-demand cache; no Google request on a valid hit. */
    public synchronized Caption caption(String videoId) {
        var credential = tokens.requireCredential();
        String scopes = credential.getScopes();
        if (scopes == null || !List.of(scopes.split("\\s+")).contains(YoutubeOAuthService.PLAYLIST_SCOPE)) {
            return Caption.unavailable("PERMISSION_REQUIRED");
        }
        String channel = credential.getChannelId();
        if (channel == null || channel.isBlank() || videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            return Caption.unavailable("INVALID_CONTEXT");
        }
        try {
            var cached = cache.find(channel, videoId);
            if (cached.isPresent()) return cached.get();
        } catch (RuntimeException e) {
            log.warn("caption_cache_read_failed type={}", e.getClass().getSimpleName());
            return Caption.unavailable("CACHE_UNAVAILABLE");
        }
        Caption caption = fetchCaption(videoId);
        if ("AVAILABLE".equals(caption.state()) || "NOT_AVAILABLE".equals(caption.state())) {
            try {
                if (channel.equals(tokens.requireCredential().getChannelId())) cache.save(channel, videoId, caption);
                else return Caption.unavailable("CHANNEL_CHANGED");
            } catch (RuntimeException e) {
                log.warn("caption_cache_write_failed type={}", e.getClass().getSimpleName());
            }
        }
        return caption;
    }

    private Caption fetchCaption(String videoId) {
        String token = tokens.getValidAccessToken();
        try {
            JsonNode result = rest.get()
                    .uri(API + "/captions?part=snippet&videoId={videoId}", videoId)
                    .headers(h -> h.setBearerAuth(token)).retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw new GoogleApiException(res.getStatusCode().value(), "captions.list", null);
                    }).body(JsonNode.class);
            if (result == null || !result.path("items").isArray()) {
                return Caption.unavailable("API_UNAVAILABLE");
            }
            List<JsonNode> tracks = new ArrayList<>();
            for (JsonNode item : result.path("items")) {
                JsonNode snippet = item.path("snippet");
                if ("serving".equals(snippet.path("status").asText())
                        && !"forced".equals(snippet.path("trackKind").asText()) && !item.path("id").asText().isBlank()) {
                    tracks.add(item);
                }
            }
            return tracks.stream().min(Comparator.comparingInt(YouTubeThumbnailClient::captionPriority))
                    .map(track -> downloadCaption(track, token))
                    .orElseGet(() -> Caption.unavailable("NOT_AVAILABLE"));
        } catch (GoogleApiException e) {
            return Caption.unavailable(e.getStatusCode() == 403 ? "PERMISSION_OR_QUOTA" : "API_UNAVAILABLE");
        } catch (org.springframework.web.client.RestClientException e) {
            return Caption.unavailable("API_UNAVAILABLE");
        }
    }

    private static int captionPriority(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        String language = snippet.path("language").asText().toLowerCase(Locale.ROOT);
        boolean italian = language.equals("it") || language.startsWith("it-");
        return (italian ? 0 : 2) + ("ASR".equalsIgnoreCase(snippet.path("trackKind").asText()) ? 1 : 0);
    }

    private Caption downloadCaption(JsonNode track, String token) {
        byte[] bytes = rest.get()
                .uri(API + "/captions/{id}?tfmt=vtt", track.path("id").asText())
                .headers(h -> h.setBearerAuth(token)).retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new GoogleApiException(res.getStatusCode().value(), "captions.download", null);
                }).body(byte[].class);
        if (bytes == null || bytes.length == 0) return Caption.unavailable("NOT_AVAILABLE");
        var excerpt = CaptionExcerpt.fromVtt(new String(bytes, StandardCharsets.UTF_8));
        if (excerpt.text().isBlank()) return Caption.unavailable("NOT_AVAILABLE");
        JsonNode snippet = track.path("snippet");
        return new Caption("AVAILABLE", snippet.path("language").asText(), snippet.path("trackKind").asText(),
                excerpt.text(), excerpt.truncated());
    }

    public void setThumbnail(String videoId, byte[] jpeg) {
        tokens.requireUploadScope();
        String token = tokens.getValidAccessToken();
        JsonNode result = rest.post().uri(UPLOAD + "?videoId={id}&uploadType=media", videoId)
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.IMAGE_JPEG).body(jpeg).retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new GoogleApiException(res.getStatusCode().value(), "thumbnails.set", null);
                }).body(JsonNode.class);
        if (result == null || !result.path("items").isArray() || result.path("items").isEmpty()) {
            throw new IllegalStateException("YouTube non ha confermato la miniatura");
        }
    }

    // webVtt is the legacy MCP field name; it now contains a plain-text excerpt.
    public record Caption(String state, String language, String source, String webVtt, boolean truncated) {
        static Caption unavailable(String state) {
            return new Caption(state, null, null, null, false);
        }
    }
}
