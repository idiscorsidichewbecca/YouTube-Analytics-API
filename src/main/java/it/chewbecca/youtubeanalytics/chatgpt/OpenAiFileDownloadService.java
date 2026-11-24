package it.chewbecca.youtubeanalytics.chatgpt;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** Scarica esclusivamente da host HTTPS esplicitamente autorizzati. Redirect vietati (anti-SSRF). */
@Service
public class OpenAiFileDownloadService {
    static final long MAX_FILE_BYTES = 300L * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ChatGptBridgeProperties settings;

    public OpenAiFileDownloadService(ChatGptBridgeProperties settings) { this.settings = settings; }

    public record FileInput(@JsonProperty("download_url") String downloadUrl,
                            @JsonProperty("file_id") String fileId,
                            @JsonProperty("mime_type") String mimeType,
                            @JsonProperty("file_name") String fileName) {}

    URI validate(FileInput file) {
        if (file == null || !StringUtils.hasText(file.downloadUrl()) ||
                !StringUtils.hasText(file.fileId()) || !file.fileId().matches("file_[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("Allega un file valido tramite ChatGPT");
        }
        if (file.fileName() != null && !file.fileName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("Il video deve essere MP4");
        }
        if (StringUtils.hasText(file.mimeType()) && !"video/mp4".equalsIgnoreCase(file.mimeType()) &&
                !"application/octet-stream".equalsIgnoreCase(file.mimeType())) {
            throw new IllegalArgumentException("Tipo MIME non supportato: serve MP4");
        }
        URI uri;
        try { uri = URI.create(file.downloadUrl()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("URL file non valido", ex); }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null) {
            throw new IllegalArgumentException("URL file non autorizzato");
        }
        boolean allowed = settings.allowedFileHosts().stream()
                .anyMatch(h -> h != null && host.equalsIgnoreCase(h.trim()));
        if (!allowed) {
            throw new IllegalArgumentException("Host download non autorizzato (configura CHATGPT_FILE_ALLOWED_HOSTS)");
        }
        return uri;
    }

    /** Il chiamante deve eliminare il Path nel blocco finally. */
    public Path download(FileInput file) {
        URI uri = validate(file);
        Path path = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(12))
                    .header("Accept", "video/mp4,application/octet-stream,*/*")
                    .GET().build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                throw new IllegalStateException("File ChatGPT non disponibile (HTTP " + res.statusCode() + ")");
            }
            long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_FILE_BYTES) {
                res.body().close();
                throw new IllegalArgumentException("MP4 oltre 300MB");
            }
            path = Files.createTempFile("chewbecca-chatgpt-", ".mp4");
            try (InputStream input = res.body(); var out = Files.newOutputStream(path)) {
                byte[] chunk = new byte[64 * 1024];
                long count = 0;
                int n;
                while ((n = input.read(chunk)) != -1) {
                    count += n;
                    if (count > MAX_FILE_BYTES) throw new IllegalArgumentException("MP4 oltre 300MB");
                    out.write(chunk, 0, n);
                }
            }
            if (Files.size(path) < 12 || !isMp4(path)) {
                throw new IllegalArgumentException("Il file non sembra un MP4 valido (signature ftyp mancante)");
            }
            Path completed = path;
            path = null;
            return completed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrotto", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossibile scaricare il video ChatGPT", ex);
        } finally {
            if (path != null) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
    }

    private static boolean isMp4(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(12);
            return header.length == 12 && header[4] == 'f' && header[5] == 't' &&
                    header[6] == 'y' && header[7] == 'p';
        }
    }
}
