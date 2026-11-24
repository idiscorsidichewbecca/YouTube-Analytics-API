package it.chewbecca.youtubeanalytics.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Legge il calendario del canale senza cache, includendo Shorts e video normali. */
@Service
public class YouTubePublicationCalendar {
    private static final String API = "https://www.googleapis.com/youtube/v3";
    private final RestClient rest;
    private final GoogleAccessTokenService tokens;

    public YouTubePublicationCalendar(RestClient googleRestClient, GoogleAccessTokenService tokens) {
        this.rest = googleRestClient;
        this.tokens = tokens;
    }

    /**
     * Anche i video privati programmati sono nella playlist uploads del proprietario.
     * status.publishAt e' il calendario di pubblicazione; snippet.publishedAt
     * viene usato solo per contenuti gia' pubblici o non in elenco.
     * Un errore API interrompe il piano (fail closed), non presume che il calendario sia vuoto.
     */
    public List<Publication> between(Instant from, Instant until) {
        String accessToken = tokens.getValidAccessToken();
        String uploads = tokens.requireCredential().getUploadsPlaylistId();
        if (uploads == null || uploads.isBlank()) {
            throw new IllegalStateException("Playlist uploads del canale assente: impossibile verificare le sovrapposizioni");
        }
        List<String> videoIds = new ArrayList<>();
        String pageToken = null;
        do {
            var builder = UriComponentsBuilder.fromUriString(API + "/playlistItems")
                    .queryParam("part", "contentDetails")
                    .queryParam("playlistId", uploads)
                    .queryParam("maxResults", 50);
            if (pageToken != null) builder.queryParam("pageToken", pageToken);
            JsonNode root = get(builder.build().encode().toUri(), accessToken, "playlistItems.list");
            for (JsonNode item : root.path("items")) {
                String id = item.path("contentDetails").path("videoId").asText("");
                if (!id.isBlank()) videoIds.add(id);
            }
            pageToken = nonBlank(root.path("nextPageToken").asText(""));
        } while (pageToken != null);

        List<Publication> events = new ArrayList<>();
        for (int i = 0; i < videoIds.size(); i += 50) {
            var uri = UriComponentsBuilder.fromUriString(API + "/videos")
                    .queryParam("part", "snippet,status,liveStreamingDetails")
                    .queryParam("id", String.join(",", videoIds.subList(i, Math.min(i + 50, videoIds.size()))))
                    .build().encode().toUri();
            JsonNode root = get(uri, accessToken, "videos.list calendar");
            for (JsonNode item : root.path("items")) {
                JsonNode status = item.path("status");
                Instant at = parse(status.path("publishAt").asText(null));
                JsonNode liveDetails = item.path("liveStreamingDetails");
                if (at == null && !liveDetails.hasNonNull("actualStartTime")) {
                    at = parse(liveDetails.path("scheduledStartTime").asText(null));
                }
                boolean scheduled = at != null;
                if (!scheduled && !"private".equals(status.path("privacyStatus").asText(""))) {
                    at = parse(item.path("snippet").path("publishedAt").asText(null));
                }
                if (at != null && !at.isBefore(from) && at.isBefore(until)) {
                    events.add(new Publication(item.path("id").asText(),
                            item.path("snippet").path("title").asText(""), at, scheduled));
                }
            }
        }
        return List.copyOf(events);
    }

    private JsonNode get(URI uri, String token, String operation) {
        JsonNode root = rest.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(s -> s.isError(), (request, response) -> {
                    // La risposta può essere utile all'interno dell'eccezione, mai riportare il token.
                    throw new GoogleApiException(response.getStatusCode().value(), operation,
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                }).body(JsonNode.class);
        if (root == null || !root.path("items").isArray()) {
            throw new IllegalStateException("Risposta incompleta da " + operation + ": calendario non verificabile");
        }
        return root;
    }

    private static String nonBlank(String s) { return s == null || s.isBlank() ? null : s; }
    private static Instant parse(String s) {
        if (s == null || s.isBlank()) return null;
        return Instant.parse(s);
    }

    public record Publication(String videoId, String title, Instant publishAt, boolean scheduled) {}
}
