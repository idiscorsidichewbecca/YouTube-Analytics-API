package it.chewbecca.youtubeanalytics.performance;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.client.YouTubeStudioClient.DATA;

@Component
public class YouTubeVideoChanges {
    private final YouTubeStudioClient youtube;

    public YouTubeVideoChanges(YouTubeStudioClient youtube) { this.youtube = youtube; }

    public Current read(String owner, String id) {
        var video = youtube.get(DATA, "videos", Map.of("id", id, "part", "snippet")).path("items").path(0);
        var snippet = video.path("snippet");
        if (!owner.equals(snippet.path("channelId").asText())) {
            throw new IllegalArgumentException("Video del canale non trovato");
        }
        return new Current(video.path("etag").asText(null), snippet);
    }

    public void update(String id, Current current, String action, String value) {
        var properties = new LinkedHashMap<String, Object>();
        for (String key : List.of("title", "description", "categoryId", "tags", "defaultLanguage", "defaultAudioLanguage")) {
            if (current.snippet().hasNonNull(key)) properties.put(key, current.snippet().get(key));
        }
        if (!properties.containsKey("title") || !properties.containsKey("categoryId")) {
            throw new IllegalStateException("Dati del video incompleti per aggiornare lo snippet");
        }
        properties.put(action.equals("TITLE") ? "title" : "description", value);
        var response = youtube.write(DATA, "videos", Map.of("part", "snippet"), Map.of("id", id, "snippet", properties),
                true, current.etag());
        if (response == null || !id.equals(response.path("id").asText())) {
            throw new IllegalStateException("YouTube non ha confermato la modifica");
        }
    }

    public record Current(String etag, JsonNode snippet) {
        public String value(String action) {
            return switch (action) {
                case "TITLE" -> snippet.path("title").asText("");
                case "DESCRIPTION" -> snippet.path("description").asText("");
                case "THUMBNAIL" -> snippet.path("thumbnails").toString();
                default -> throw new IllegalArgumentException("Intervento sconosciuto");
            };
        }
    }
}
