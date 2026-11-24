package it.chewbecca.youtubeanalytics.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Assegnazione idempotente a una playlist esistente; non crea playlist ne' ricarica video. */
@Service
public class YouTubePlaylistService {
    private static final String API = "https://www.googleapis.com/youtube/v3";
    private final RestClient rest;
    private final GoogleAccessTokenService tokens;
    private final ObjectMapper json;
    private final String targetTitle;

    public YouTubePlaylistService(RestClient googleRestClient, GoogleAccessTokenService tokens,
                                  ObjectMapper json,
                                  @Value("${app.youtube.short-playlist-title:Lo sapevi?}") String targetTitle) {
        this.rest = googleRestClient;
        this.tokens = tokens;
        this.json = json;
        this.targetTitle = targetTitle;
    }

    /** Pre-flight prima del caricamento: previene video caricato ma playlist non autorizzata. */
    public void checkReady() {
        String bearer = tokens.getValidAccessToken();
        tokens.requirePlaylistScope();
        findTargetPlaylist(bearer);
    }

    public synchronized PlaylistResult ensureInTargetPlaylist(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Identificativo YouTube non valido");
        }
        String bearer = tokens.getValidAccessToken();
        tokens.requirePlaylistScope();
        String playlistId = findTargetPlaylist(bearer);
        URI checkUri = UriComponentsBuilder.fromUriString(API + "/playlistItems")
                .queryParam("part", "id")
                .queryParam("playlistId", playlistId)
                .queryParam("videoId", videoId)
                .build().encode().toUri();
        JsonNode existing = get(checkUri, bearer, "playlistItems.list check");
        if (existing.path("items").size() > 0) {
            return new PlaylistResult(playlistId, targetTitle, "ALREADY_PRESENT", videoId);
        }
        ObjectNode body = json.createObjectNode();
        ObjectNode snippet = body.putObject("snippet");
        snippet.put("playlistId", playlistId);
        ObjectNode resource = snippet.putObject("resourceId");
        resource.put("kind", "youtube#video");
        resource.put("videoId", videoId);
        URI insertUri = URI.create(API + "/playlistItems?part=snippet");
        JsonNode response = rest.post().uri(insertUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve()
                .onStatus(s -> s.isError(), (request, res) -> {
                    throw new GoogleApiException(res.getStatusCode().value(), "playlistItems.insert",
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                }).body(JsonNode.class);
        if (response == null || response.path("id").asText("").isBlank()) {
            throw new IllegalStateException("Google non ha confermato l'inserimento nella playlist");
        }
        return new PlaylistResult(playlistId, targetTitle, "ADDED", videoId);
    }

    private String findTargetPlaylist(String bearer) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            var builder = UriComponentsBuilder.fromUriString(API + "/playlists")
                    .queryParam("part", "snippet")
                    .queryParam("mine", true)
                    .queryParam("maxResults", 50);
            if (pageToken != null) builder.queryParam("pageToken", pageToken);
            JsonNode page = get(builder.build().encode().toUri(), bearer, "playlists.list mine");
            for (JsonNode item : page.path("items")) {
                if (targetTitle.trim().equalsIgnoreCase(item.path("snippet").path("title").asText("").trim())) {
                    String id = item.path("id").asText("");
                    if (!id.isBlank()) ids.add(id);
                }
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        if (ids.isEmpty()) throw new IllegalStateException("Playlist '" + targetTitle + "' non trovata nel canale");
        if (ids.size() != 1) throw new IllegalStateException("Piu' playlist chiamate '" + targetTitle +
                "': impossibile scegliere senza rischio di assegnazione errata");
        return ids.getFirst();
    }

    private JsonNode get(URI uri, String bearer, String operation) {
        JsonNode result = rest.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .onStatus(s -> s.isError(), (request, res) -> {
                    throw new GoogleApiException(res.getStatusCode().value(), operation,
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                }).body(JsonNode.class);
        if (result == null || !result.path("items").isArray()) {
            throw new IllegalStateException("Risposta playlist incompleta da Google");
        }
        return result;
    }

    public record PlaylistResult(String playlistId, String playlistTitle, String status, String videoId) {}
}
