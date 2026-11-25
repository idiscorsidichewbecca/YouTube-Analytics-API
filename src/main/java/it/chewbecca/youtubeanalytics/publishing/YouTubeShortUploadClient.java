package it.chewbecca.youtubeanalytics.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Sessione ufficiale Google videos.insert (uploadType=resumable), con un singolo PUT di contenuto. */
@Component
public class YouTubeShortUploadClient {
    private static final URI INSERT_URI = URI.create(
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status");
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final GoogleAccessTokenService tokens;
    private final ObjectMapper mapper;

    public YouTubeShortUploadClient(GoogleAccessTokenService tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public static void validateFile(MultipartFile multipart) {
        if (multipart == null) throw new IllegalArgumentException("Video MP4 mancante");
        if (multipart.isEmpty() || multipart.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("MP4 richiesto, dimensione massima 300 MB");
        }
        if (multipart.getOriginalFilename() == null ||
                !multipart.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("Sono ammessi soltanto file .mp4");
        }
    }

    public String schedule(MultipartFile multipart, String title, String description, Instant when, boolean madeForKids) {
        validateFile(multipart);
        Path temp = null;
        try {
            temp = Files.createTempFile("chewbecca-short-", ".mp4");
            multipart.transferTo(temp);
            long size = Files.size(temp);
            ObjectNode metadata = mapper.createObjectNode();
            ObjectNode snippet = metadata.putObject("snippet");
            snippet.put("title", title);
            snippet.put("description", description);
            snippet.put("categoryId", "24");
            ObjectNode status = metadata.putObject("status");
            status.put("privacyStatus", "private");
            status.put("publishAt", when.toString());
            status.put("selfDeclaredMadeForKids", madeForKids);

            HttpRequest begin = HttpRequest.newBuilder(INSERT_URI)
                    .timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer " + tokens.getValidAccessToken())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Upload-Content-Type", "video/mp4")
                    .header("X-Upload-Content-Length", Long.toString(size))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(metadata)))
                    .build();
            HttpResponse<String> init = httpClient.send(begin, HttpResponse.BodyHandlers.ofString());
            if (init.statusCode() != 200 && init.statusCode() != 201) {
                throw new GoogleApiException(init.statusCode(), "videos.insert init", "Upload non inizializzato");
            }
            String url = init.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Google non ha restituito la sessione upload"));
            URI session = URI.create(url);
            if (!"https".equalsIgnoreCase(session.getScheme()) ||
                    !("www.googleapis.com".equalsIgnoreCase(session.getHost()) ||
                      "upload.youtube.com".equalsIgnoreCase(session.getHost()))) {
                throw new IllegalStateException("URL sessione upload inatteso da Google");
            }
            HttpRequest upload = HttpRequest.newBuilder(session)
                    .timeout(Duration.ofMinutes(25))
                    .header("Content-Type", "video/mp4")
                    .PUT(HttpRequest.BodyPublishers.ofFile(temp))
                    .build();
            HttpResponse<String> uploaded = httpClient.send(upload, HttpResponse.BodyHandlers.ofString());
            if (uploaded.statusCode() != 200 && uploaded.statusCode() != 201) {
                // Il risultato potrebbe essere incerto: non ritentare automaticamente il POST iniziale.
                throw new GoogleApiException(uploaded.statusCode(), "videos.insert upload", "Caricamento non confermato");
            }
            JsonNode response = mapper.readTree(uploaded.body());
            String videoId = response.path("id").asText("");
            if (videoId.isBlank()) throw new IllegalStateException("Upload senza identificativo video");
            String privacy = response.path("status").path("privacyStatus").asText("");
            String schedule = response.path("status").path("publishAt").asText("");
            if (!"private".equals(privacy) || schedule.isBlank()) {
                throw new IllegalStateException(
                        "Upload completato ma YouTube non ha confermato la programmazione: verifica YouTube Studio");
            }
            return videoId;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Upload interrotto: verifica il canale prima di riprovare", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Upload non confermato: verifica il canale prima di riprovare", ex);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* file temporaneo */ }
            }
        }
    }
}
