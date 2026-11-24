package it.chewbecca.youtubeanalytics.chatgpt;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Scarica allegati MP4 da host HTTPS esplicitamente autorizzati.
 * Redirect vietati: un 3xx è un errore diagnostico, mai un motivo per seguire URL non verificati.
 * Nei log non si registrano URL firmati, query string, file_id o token.
 */
@Service
public class OpenAiFileDownloadService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiFileDownloadService.class);
    static final long MAX_FILE_BYTES = 300L * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ChatGptBridgeProperties settings;

    public OpenAiFileDownloadService(ChatGptBridgeProperties settings) { this.settings = settings; }

    public record FileInput(@JsonProperty("download_url") String downloadUrl,
                            @JsonProperty("file_id") String fileId,
                            @JsonProperty("mime_type") String mimeType,
                            @JsonProperty("file_name") String fileName) {}

    public enum DownloadError {
        INVALID_FILE, INVALID_URL, HOST_NOT_ALLOWED, REDIRECT_BLOCKED,
        HTTP_ERROR, FILE_TOO_LARGE, INVALID_MP4, NETWORK_ERROR, INTERRUPTED
    }

    /** Errore sicuro da restituire come risultato MCP: non contiene credenziali né URL. */
    public static final class AttachmentDownloadException extends RuntimeException {
        private final DownloadError code;
        public AttachmentDownloadException(DownloadError code, String message) {
            super(message);
            this.code = code;
        }
        public AttachmentDownloadException(DownloadError code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
        public DownloadError code() { return code; }
    }

    URI validate(FileInput file) {
        if (file == null || !StringUtils.hasText(file.downloadUrl()) ||
                !StringUtils.hasText(file.fileId()) || !file.fileId().matches("file_[A-Za-z0-9_-]{8,128}")) {
            throw error(DownloadError.INVALID_FILE, "Allegato ChatGPT mancante o identificativo file non valido");
        }
        if (file.fileName() != null && !file.fileName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw error(DownloadError.INVALID_FILE, "Il file allegato deve essere MP4");
        }
        if (StringUtils.hasText(file.mimeType()) && !"video/mp4".equalsIgnoreCase(file.mimeType()) &&
                !"application/octet-stream".equalsIgnoreCase(file.mimeType())) {
            throw error(DownloadError.INVALID_FILE, "Il tipo del file allegato non è MP4");
        }
        URI uri;
        try { uri = URI.create(file.downloadUrl()); }
        catch (IllegalArgumentException ex) {
            throw error(DownloadError.INVALID_URL, "URL dell'allegato ChatGPT non valido");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null) {
            throw error(DownloadError.INVALID_URL, "L'allegato richiede un URL HTTPS valido");
        }
        boolean allowed = settings.allowedFileHosts().stream()
                .anyMatch(h -> h != null && host.equalsIgnoreCase(h.trim()));
        if (!allowed) {
            // Host passato come argomento strutturato, mai URL completo / query string.
            log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} uploadStarted=false", DownloadError.HOST_NOT_ALLOWED, host);
            throw new AttachmentDownloadException(DownloadError.HOST_NOT_ALLOWED,
                    "Host dell'allegato non autorizzato. Verificare CHATGPT_FILE_ALLOWED_HOSTS nei log Render");
        }
        return uri;
    }

    /** Il chiamante deve eliminare il Path nel blocco finally. */
    public Path download(FileInput file) {
        URI uri = validate(file);
        String host = uri.getHost();
        Path path = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(12))
                    .header("Accept", "video/mp4,application/octet-stream,*/*")
                    .GET().build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = res.statusCode();
            if (status != 200) {
                res.body().close();
                if (status >= 300 && status < 400) {
                    // Non seguire mai il Location header, che potrebbe puntare a una rete privata.
                    log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} httpStatus={} uploadStarted=false",
                            DownloadError.REDIRECT_BLOCKED, host, status);
                    throw new AttachmentDownloadException(DownloadError.REDIRECT_BLOCKED,
                            "L'allegato ChatGPT ha restituito un redirect HTTP " + status +
                            "; download interrotto per sicurezza");
                }
                log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} httpStatus={} uploadStarted=false",
                        DownloadError.HTTP_ERROR, host, status);
                throw new AttachmentDownloadException(DownloadError.HTTP_ERROR,
                        "Allegato ChatGPT non scaricabile: HTTP " + status);
            }
            long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_FILE_BYTES) {
                res.body().close();
                throw error(DownloadError.FILE_TOO_LARGE, "MP4 oltre 300MB");
            }
            path = Files.createTempFile("chewbecca-chatgpt-", ".mp4");
            try (InputStream input = res.body(); var out = Files.newOutputStream(path)) {
                byte[] chunk = new byte[64 * 1024];
                long count = 0;
                int n;
                while ((n = input.read(chunk)) != -1) {
                    count += n;
                    if (count > MAX_FILE_BYTES) throw error(DownloadError.FILE_TOO_LARGE, "MP4 oltre 300MB");
                    out.write(chunk, 0, n);
                }
            }
            if (Files.size(path) < 12 || !isMp4(path)) {
                throw error(DownloadError.INVALID_MP4, "L'allegato non contiene un MP4 valido (ftyp mancante)");
            }
            Path completed = path;
            path = null;
            return completed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} uploadStarted=false",
                    DownloadError.INTERRUPTED, host);
            throw new AttachmentDownloadException(DownloadError.INTERRUPTED, "Download allegato interrotto", ex);
        } catch (IOException ex) {
            log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} uploadStarted=false exceptionType={}",
                    DownloadError.NETWORK_ERROR, host, ex.getClass().getSimpleName());
            throw new AttachmentDownloadException(DownloadError.NETWORK_ERROR,
                    "Errore di rete durante il download dell'allegato ChatGPT", ex);
        } catch (AttachmentDownloadException ex) {
            if (ex.code() != DownloadError.HTTP_ERROR && ex.code() != DownloadError.REDIRECT_BLOCKED) {
                log.warn("MCP_FILE_DOWNLOAD_FAILED code={} host={} uploadStarted=false", ex.code(), host);
            }
            throw ex;
        } finally {
            if (path != null) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
    }

    private static AttachmentDownloadException error(DownloadError code, String message) {
        return new AttachmentDownloadException(code, message);
    }

    private static boolean isMp4(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(12);
            return header.length == 12 && header[4] == 'f' && header[5] == 't' &&
                    header[6] == 'y' && header[7] == 'p';
        }
    }
}
