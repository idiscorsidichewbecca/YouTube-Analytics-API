package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class InstagramBridgeClient {
    private final RestClient client;

    public InstagramBridgeClient(String baseUrl, String token) {
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || token.length() < 32) {
            throw new IllegalArgumentException("Instagram bridge configuration invalid");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token).build();
    }

    public JsonNode prepare(byte[] image, String kind, String link, String question, String optionsJson) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("image", new ByteArrayResource(image) {
            @Override public String getFilename() { return "story.jpg"; }
        });
        body.add("kind", kind);
        body.add("link", link);
        body.add("question", question);
        body.add("options_json", optionsJson);
        return safe(() -> client.post().uri("/stories/drafts").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(JsonNode.class));
    }

    public JsonNode quizHistory() {
        return safe(() -> client.get().uri("/stories/quiz-history").retrieve().body(JsonNode.class));
    }

    public JsonNode status(UUID id) {
        return safe(() -> client.get().uri("/stories/drafts/{id}", id).retrieve().body(JsonNode.class));
    }

    public JsonNode command(UUID id, String action, Map<String, String> payload) {
        if (!java.util.Set.of("publish", "schedule", "cancel").contains(action)) throw new IllegalArgumentException("Invalid action");
        return safe(() -> client.post().uri("/stories/drafts/{id}/{action}", id, action)
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(JsonNode.class));
    }

    private JsonNode safe(Supplier<JsonNode> call) {
        try {
            JsonNode result = call.get();
            if (result == null) throw new IllegalStateException("Risposta Instagram assente; verifica lo stato della bozza");
            return result;
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("Instagram bridge HTTP " + ex.getStatusCode().value() + "; verifica parametri, sessione e stato della bozza; non ripetere una pubblicazione incerta");
        } catch (RestClientException ex) {
            throw new IllegalStateException("Instagram bridge non raggiungibile o esito incerto; verifica lo stato prima di riprovare");
        }
    }
}
