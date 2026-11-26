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
import java.util.regex.Pattern;

/**
 * Scarica allegati MP4 da host HTTPS esplicitamente autorizzati.
 * Redirect vietati: un 3xx è un errore diagnostico, mai un motivo per seguire URL non verificati.
 * Nei log non si registrano URL firmati, query string, file_id o token.
 */
@Service
public class OpenAiFileDownloadService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiFileDownloadService.class);
    static final long MAX_FILE_BYTES = 300L * 1024 * 1024;

    /**
     * Gli allegati ChatGPT possono provenire da host regionali OpenAI come
     * sdmntprdenmarkeast.oaiusercontent.com o sdmntprpolandcentral.oaiusercontent.com.
     * Si accetta SOLO un singolo hostname con prefisso sdmntpr sotto oaiusercontent.com:
     * non un generico wildcard *.oaiusercontent.com e non domini con suffissi imitati.
     */
    private static final Pattern OPENAI_REGIONAL_FILE_HOST = Pattern.compile(
            "sdmntpr[a-z0-9](?:[a-z0-9-]{0,53}[a-z0-9])?\\.oaiusercontent\\.com",
            Pattern.CASE_INSENSITIVE);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ChatGptBridgeProperties settings;

    public OpenAiFileDownloadService(ChatGptBridgeProperties settings) { this.settings = settings; }

    public record FileInput(@JsonProperty("download_url") String downloadUrl,
                            @JsonProperty("file_id") String fileId,
                            @JsonProperty("mime_type") String mimeType,
                            @JsonProperty("file_name") String fileName) {}

    /**
     * Diagnose the raw MCP attachment shape without logging any supplied values,
     * unknown key names, filenames, signed URLs, IDs or credentials.
     */
    public static void diagnoseAttachment(String operation, Object attachment) {
        java.util.Map<?, ?> raw = attachment instanceof java.util.Map<?, ?> map ? map : java.util.Map.of();
        var knownKeys = java.util.List.of("download_url", "file_id", "mime_type", "file_name",
                "downloadUrl", "fileId", "mimeType", "fileName", "id", "url");
        var receivedKeys = knownKeys.stream().filter(raw::containsKey).toList();
        String shape = attachment == null ? "missing" : attachment instanceof java.util.Map ? "object"
                : attachment instanceof String ? "string" : attachment instanceof java.util.List ? "array" : "other";
        boolean urlPresent = raw.get("download_url") instanceof String url && StringUtils.hasText(url);
        boolean idPresent = raw.get("file_id") instanceof String id && StringUtils.hasText(id);
        boolean idValid = raw.get("file_id") instanceof String id && isValidFileId(id);
        log.info("MCP_ATTACHMENT_DIAGNOSTICS operation={} shape={} attachmentPresent={} receivedKeys={} unknownKeyCount={} downloadUrlPresent={} fileIdPresent={} fileIdValid={}",
                operation, shape, attachment != null, receivedKeys, raw.size() - receivedKeys.size(),
                urlPresent, idPresent, idValid);
    }

    /**
     * The connector's file reference is opaque, not necessarily a Files API ID.
     * Keep it bounded (the Shorts batch column is VARCHAR(133)) and reject whitespace
     * and control characters. It is never used as a URL, path or authorization token.
     */
    public static boolean isValidFileId(String id) {
        return StringUtils.hasText(id) && id.length() <= 133
                && id.codePoints().noneMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c));
    }

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
        return validate(file, false);
    }

    private URI validate(FileInput file, boolean image) {
        boolean downloadUrlPresent = file != null && StringUtils.hasText(file.downloadUrl());
        boolean fileIdPresent = file != null && StringUtils.hasText(file.fileId());
        boolean fileIdValid = fileIdPresent && isValidFileId(file.fileId());
        if (!downloadUrlPresent || !fileIdValid) {
            log.warn("MCP_FILE_VALIDATION_FAILED attachmentPresent={} downloadUrlPresent={} fileIdPresent={} fileIdValid={} uploadStarted=false",
                    file != null, downloadUrlPresent, fileIdPresent, fileIdValid);
            throw error(DownloadError.INVALID_FILE,
                    "Allegato ChatGPT mancante o identificativo file non valido"
                    + " (attachmentPresent=" + (file != null)
                    + ", downloadUrlPresent=" + downloadUrlPresent
                    + ", fileIdPresent=" + fileIdPresent
                    + ", fileIdValid=" + fileIdValid + ")");
        }
        String filename = file.fileName() == null ? "" : file.fileName().toLowerCase(Locale.ROOT);
        String mime = file.mimeType() == null ? "" : file.mimeType().toLowerCase(Locale.ROOT);
        boolean validName = image ? filename.endsWith(".jpg") || filename.endsWith(".jpeg")
                || filename.endsWith(".png") : filename.endsWith(".mp4");
        boolean validMime = image ? mime.equals("image/jpeg") || mime.equals("image/png") : mime.equals("video/mp4");
        if ((file.fileName() != null && !validName)
                || (StringUtils.hasText(mime) && !validMime && !mime.equals("application/octet-stream"))) {
            throw error(DownloadError.INVALID_FILE, image ? "Allega un'immagine JPG o PNG" : "Allega un file MP4");
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
        // Host esatti configurati + sottodomini regionali OpenAI con pattern rigoroso.
        // Il controllo è sul valore URI.getHost(), non sul testo dell'URL.
        boolean allowed = OPENAI_REGIONAL_FILE_HOST.matcher(host).matches() ||
                settings.allowedFileHosts().stream()
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
        return download(file, false);
    }

    public Path downloadImage(FileInput file) {
        return download(file, true);
    }

    private Path download(FileInput file, boolean image) {
        URI uri = image ? validate(file, true) : validate(file);
        long maxBytes = image ? 20L * 1024 * 1024 : MAX_FILE_BYTES;
        String host = uri.getHost();
        Path path = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(12))
                    .header("Accept", image ? "image/jpeg,image/png,application/octet-stream" : "video/mp4,application/octet-stream,*/*")
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
            if (contentLength > maxBytes) {
                res.body().close();
                throw error(DownloadError.FILE_TOO_LARGE, image ? "Immagine oltre 20 MB" : "MP4 oltre 300MB");
            }
            path = Files.createTempFile("chewbecca-chatgpt-", image ? ".img" : ".mp4");
            try (InputStream input = res.body(); var out = Files.newOutputStream(path)) {
                byte[] chunk = new byte[64 * 1024];
                long count = 0;
                int n;
                while ((n = input.read(chunk)) != -1) {
                    count += n;
                    if (count > maxBytes) throw error(DownloadError.FILE_TOO_LARGE, image ? "Immagine oltre 20 MB" : "MP4 oltre 300MB");
                    out.write(chunk, 0, n);
                }
            }
            if (!image && (Files.size(path) < 12 || !isMp4(path))) {
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
