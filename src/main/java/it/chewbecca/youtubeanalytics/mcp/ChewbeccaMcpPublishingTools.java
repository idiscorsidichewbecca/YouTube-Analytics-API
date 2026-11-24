package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.chatgpt.TemporaryVideoMultipart;
import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * MCP write-tools for ChatGPT: explicit preview + explicit confirmation before upload.
 * Uses ChatGPT's fileParams extension rather than passing 300 MB of video in JSON.
 * Neither this bean nor the caller exposes the Google OAuth token to ChatGPT.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class ChewbeccaMcpPublishingTools {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final String WRITE_SCOPE = "SCOPE_shorts:write";
    private static final Map<String, Object> READ_SECURITY = Map.of(
            "securitySchemes", List.of(Map.of("type", "oauth2", "scopes", List.of("shorts:read"))));
    private static final Map<String, Object> WRITE_SECURITY = Map.of(
            "securitySchemes", List.of(Map.of("type", "oauth2", "scopes", List.of("shorts:read", "shorts:write"))));

    /** Tool descriptors must explicitly advertise fileParams; annotations alone don't support it. */
    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> chewbeccaPublishingTools(
            ShortPublishingService publishing, OpenAiFileDownloadService files, ObjectMapper json,
            @Value("${app.mcp.public-url}") String mcpBaseUrl) {
        return List.of(
            new McpStatelessServerFeatures.SyncToolSpecification(prepareTool(), (context, request) -> {
                try {
                    Map<String, Object> args = request.arguments();
                    String brief = required(args, "brief");
                    String title = required(args, "title");
                    String description = required(args, "description");
                    String dateTime = required(args, "publish_at");
                    boolean madeForKids = requiredBoolean(args, "made_for_kids");
                    Instant when = parseDateTime(dateTime);
                    ShortDraft draft = publishing.prepareFromChatAt(brief, title, description, madeForKids, when);
                    return ok(json, Map.of(
                            "draftId", draft.getId(), "status", draft.getStatus().name(),
                            "title", draft.getTitle(), "description", draft.getDescription(),
                            "publishAtRome", draft.getPublishAt().atZone(ROME).toString(),
                            "madeForKids", draft.isMadeForKids(),
                            "confirmationText", "CONFERMO " + draft.getId(),
                            "message", "Bozza salvata. MOSTRA titolo, descrizione, orario e stato ai fini della conferma. NON invocare schedule senza consenso esplicito dell'utente."));
                } catch (RuntimeException e) {
                    return failure(e);
                }
            }),
            new McpStatelessServerFeatures.SyncToolSpecification(scheduleTool(), (context, request) -> {
                try {
                    // Secondo livello di autorizzazione: il vecchio token shorts:read NON può pubblicare.
                    requireWriteScope();
                    Map<String, Object> args = request.arguments();
                    String draftId = required(args, "draft_id");
                    String confirmation = required(args, "confirmation_text");
                    if (!("CONFERMO " + draftId).equals(confirmation)) {
                        throw new IllegalArgumentException("Manca la conferma esplicita: CONFERMO " + draftId);
                    }
                    ShortDraft draft = publishing.getDraft(draftId);
                    if (draft.getStatus() != ShortDraft.Status.DRAFT) {
                        throw new IllegalStateException("Bozza già utilizzata o in stato incerto: " + draft.getStatus());
                    }
                    Object fileObj = args.get("file");
                    if (!(fileObj instanceof Map<?, ?> raw)) {
                        throw new IllegalArgumentException("Allega un file MP4 alla chiamata di programmazione");
                    }
                    var file = new OpenAiFileDownloadService.FileInput(
                            string(raw.get("download_url")), string(raw.get("file_id")),
                            string(raw.get("mime_type")), string(raw.get("file_name")));
                    // Il download è consentito soltanto per host OpenAI fidati, senza redirect.
                    Path downloaded = files.download(file);
                    try {
                        ShortDraft result = publishing.schedule(draftId,
                                new TemporaryVideoMultipart(downloaded, "chatgpt-short.mp4"),
                                null, null, null);
                        return ok(json, Map.of(
                                "draftId", result.getId(), "status", result.getStatus().name(),
                                "videoId", result.getYoutubeVideoId(),
                                "youtubeUrl", "https://www.youtube.com/watch?v=" + result.getYoutubeVideoId(),
                                "publishAtRome", result.getPublishAt().atZone(ROME).toString(),
                                "message", "Caricamento confermato da YouTube. Stato SCHEDULED: video privato programmato."));
                    } finally {
                        try { Files.deleteIfExists(downloaded); } catch (IOException ignored) { }
                    }
                } catch (OpenAiFileDownloadService.AttachmentDownloadException e) {
                    // File non disponibile: nessuna chiamata di upload YouTube è iniziata.
                    // Non rivelare download_url, token o file_id nel risultato MCP.
                    return attachmentFailed(e);
                } catch (SecurityException e) {
                    return insufficientScope(mcpBaseUrl);
                } catch (RuntimeException e) {
                    // Se il problema avviene DOPO il download, l'esito dell'upload
                    // potrebbe essere incerto. Non fare retry in automatico.
                    return failure(e);
                }
            })
        );
    }

    private static McpSchema.Tool prepareTool() {
        var props = Map.<String, Object>of(
                "brief", field("string", "Riassunto o trascrizione del video, min 30 caratteri. Non inventare fatti."),
                "title", field("string", "Titolo accattivante e veritiero per lo Short, max 100 caratteri."),
                "description", field("string", "Descrizione efficace e veritiera, max 5000 caratteri, hashtag pertinenti."),
                "publish_at", field("string", "Data e ora ISO 8601 con offset: es. 2026-12-10T19:30:00+01:00, Italia."),
                "made_for_kids", field("boolean", "Obbligatorio: è un video rivolto specificamente ai bambini?"));
        return McpSchema.Tool.builder()
                .name("chewbecca_prepare_short")
                .title("Prepara Short YouTube")
                .description("CREA SOLO UNA BOZZA (nessun upload). Da un breve riassunto o trascrizione genera tramite ChatGPT titolo efficace ma veritiero e descrizione pertinente; usa la data/ora italiana scelta dall'utente, convertita in ISO 8601 con offset. Mostra anteprima e richiedi conferma esplicita 'CONFERMO <draftId>'. Se manca il contenuto del video, richiedi un riassunto: non dedurlo dal nome del file. Dopo conferma puoi utilizzare chewbecca_schedule_short con il file MP4 allegato.")
                .inputSchema(new McpSchema.JsonSchema("object", props,
                        List.of("brief", "title", "description", "publish_at", "made_for_kids"), false, null, null))
                .annotations(new McpSchema.ToolAnnotations("Prepara Short", false, false, false, false, false))
                .meta(READ_SECURITY)
                .build();
    }

    private static McpSchema.Tool scheduleTool() {
        var file = Map.<String, Object>of(
                "type", "object", "properties", Map.of(
                        "download_url", Map.of("type", "string"),
                        "file_id", Map.of("type", "string"),
                        "mime_type", Map.of("type", "string"),
                        "file_name", Map.of("type", "string")),
                "required", List.of("download_url", "file_id"),
                "additionalProperties", false);
        var props = Map.<String, Object>of(
                "draft_id", field("string", "UUID della bozza approvata dall'utente."),
                "confirmation_text", field("string", "Stringa ESATTA 'CONFERMO <draft_id>' fornita esplicitamente dall'utente."),
                "file", Map.of("$ref", "#/$defs/OpenAIFile"));
        return McpSchema.Tool.builder()
                .name("chewbecca_schedule_short")
                .title("Programma Short su YouTube")
                .description("""
                        AZIONE DI SCRITTURA REALE su YouTube. Richiede consenso ESPLICITO
                        dell'utente dopo anteprima di titolo, descrizione, madeForKids e
                        data/ora. Carica MP4 allegato come privato con publishAt.
                        Richiede shorts:write.
                        Se il risultato è DOWNLOAD_FAILED, interrompi l'operazione:
                        l'upload YouTube NON è iniziato. Mostra all'utente il codice errore.
                        NON aprire YouTube Studio e NON usare browser automation come
                        alternativa. NON chiedere email o password Google. NON richiedere
                        un caricamento manuale su YouTube Studio.
                        Se fallisce la chiamata di upload, l'esito può essere incerto:
                        non effettuare retry automatici e chiedi di verificare lo stato.
                        """)
                .inputSchema(new McpSchema.JsonSchema("object", props,
                        List.of("draft_id", "confirmation_text", "file"), false,
                        Map.of("OpenAIFile", file), null))
                .annotations(new McpSchema.ToolAnnotations("Programma Short", false, false, false, true, false))
                .meta(Map.of(
                        "openai/fileParams", List.of("file"),
                        "securitySchemes", WRITE_SECURITY.get("securitySchemes"),
                        "openai/toolInvocation/invoking", "Caricamento video su YouTube…",
                        "openai/toolInvocation/invoked", "Programmazione terminata"))
                .build();
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }
    private static String string(Object value) { return value instanceof String s ? s : null; }
    private static String required(Map<String, Object> args, String name) {
        String value = string(args.get(name));
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Campo obbligatorio: " + name);
        return value.trim();
    }
    private static boolean requiredBoolean(Map<String, Object> args, String name) {
        if (!(args.get(name) instanceof Boolean value)) {
            throw new IllegalArgumentException("Campo booleano obbligatorio: " + name);
        }
        return value;
    }
    private static Instant parseDateTime(String dateTime) {
        try { return OffsetDateTime.parse(dateTime).toInstant(); }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Usa una data ISO 8601 con offset, es. 2026-12-10T19:30:00+01:00");
        }
    }
    private static void requireWriteScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(a -> WRITE_SCOPE.equals(a.getAuthority()))) {
            throw new SecurityException("Permesso shorts:write assente. Riconnetti Chewbecca Studio concedendo shorts:write.");
        }
    }
    private static McpSchema.CallToolResult ok(ObjectMapper json, Map<String, Object> value) {
        try {
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json.writeValueAsString(value))), false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Impossibile serializzare il risultato MCP", ex);
        }
    }
    private static McpSchema.CallToolResult insufficientScope(String baseUrl) {
        String metadata = baseUrl.replaceAll("/+$", "") + "/.well-known/oauth-protected-resource";
        String challenge = "Bearer resource_metadata=\"" + metadata +
                "\", scope=\"shorts:read shorts:write\", error=\"insufficient_scope\", " +
                "error_description=\"Authorization required for YouTube scheduling\"";
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Autorizzazione shorts:write necessaria: riconnetti l'app.")),
                true, null, Map.of("mcp/www_authenticate", List.of(challenge)));
    }
    private static McpSchema.CallToolResult attachmentFailed(
            OpenAiFileDownloadService.AttachmentDownloadException e) {
        // Codice machine-readable, senza URL firmati, query, file_id o token.
        String info = "DOWNLOAD_FAILED code=" + e.code()
                + " uploadStarted=false. "
                + "Il server ha bloccato il download del file prima di contattare YouTube. "
                + "Non aprire YouTube Studio, non chiedere credenziali Google e non "
                + "tentare altre modalità di pubblicazione. "
                + "Consulta i log Render (MCP_FILE_DOWNLOAD_FAILED).";
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(info)), true);
    }

    private static McpSchema.CallToolResult failure(Exception e) {
        // Non includere URL temporanei, token o stack trace nel testo restituito al client.
        String message = e.getMessage() == null ? "Operazione non completata" : e.getMessage();
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(message)), true);
    }
}
