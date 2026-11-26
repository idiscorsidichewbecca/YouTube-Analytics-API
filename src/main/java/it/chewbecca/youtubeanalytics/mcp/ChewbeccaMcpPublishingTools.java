package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.chatgpt.TemporaryVideoMultipart;
import it.chewbecca.youtubeanalytics.publishing.BatchShortPlanner;
import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import it.chewbecca.youtubeanalytics.publishing.YouTubePlaylistService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            @Value("${app.mcp.public-url}") String mcpBaseUrl,
            YouTubePlaylistService playlists) {
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
                    boolean weeklyPlan = Boolean.TRUE.equals(args.get("weekly_plan"));
                    ShortDraft draft = publishing.prepareFromChatAt(brief, title, description, madeForKids, when, weeklyPlan);
                    return ok(json, Map.of(
                            "draftId", draft.getId(), "status", draft.getStatus().name(),
                            "title", draft.getTitle(), "description", draft.getDescription(),
                            "publishAtRome", draft.getPublishAt().atZone(ROME).toString(),
                            "madeForKids", draft.isMadeForKids(),
                            "weeklyPlan", draft.isWeeklyPlan(),
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
                    if (draft.getBatchId() != null) {
                        throw new IllegalArgumentException("Usa la conferma e lo strumento di programmazione batch");
                    }
                    if (draft.getStatus() != ShortDraft.Status.DRAFT) {
                        throw new IllegalStateException("Bozza già utilizzata o in stato incerto: " + draft.getStatus());
                    }
                    if (draft.isWeeklyPlan()) {
                        playlists.checkReady();
                    }
                    OpenAiFileDownloadService.diagnoseAttachment("youtube_short", args.get("file"));
                    var file = attachment(args.get("file"));
                    // Il download è consentito soltanto per host OpenAI fidati, senza redirect.
                    Path downloaded = files.download(file);
                    try {
                        var video = new TemporaryVideoMultipart(downloaded, "chatgpt-short.mp4");
                        if (!draft.isWeeklyPlan()) {
                            ShortDraft result = publishing.schedule(draftId, video, null, null, null);
                            return ok(json, Map.of(
                                    "draftId", result.getId(), "status", result.getStatus().name(),
                                    "videoId", result.getYoutubeVideoId(),
                                    "youtubeUrl", "https://www.youtube.com/watch?v=" + result.getYoutubeVideoId(),
                                    "publishAtRome", result.getPublishAt().atZone(ROME).toString()));
                        }
                        ShortDraft result = publishing.scheduleWeekly(draftId, video);
                        return ok(json, completedWithPlaylist(result, playlists));
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

    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> chewbeccaBatchTools(
            ShortPublishingService publishing, OpenAiFileDownloadService files, ObjectMapper json,
            @Value("${app.mcp.public-url}") String mcpBaseUrl, YouTubePlaylistService playlists) {
        return List.of(
            new McpStatelessServerFeatures.SyncToolSpecification(prepareBatchTool(), (context, request) -> {
                try {
                    Map<String, Object> args = request.arguments();
                    if (!(args.get("shorts") instanceof List<?> entries) || entries.isEmpty()
                            || entries.size() > BatchShortPlanner.MAX_BATCH_SIZE) {
                        throw new IllegalArgumentException("Fornisci da 1 a 10 Shorts");
                    }
                    var batchEntries = new ArrayList<ShortPublishingService.BatchEntry>();
                    for (Object entry : entries) {
                        if (!(entry instanceof Map<?, ?> fields)) {
                            throw new IllegalArgumentException("Voce Short non valida");
                        }
                        batchEntries.add(new ShortPublishingService.BatchEntry(
                                required(fields, "filename"), required(fields, "brief"), required(fields, "title"),
                                required(fields, "description"), requiredBoolean(fields, "made_for_kids")));
                    }
                    if (args.containsKey("start_next_week") && !(args.get("start_next_week") instanceof Boolean)) {
                        throw new IllegalArgumentException("start_next_week deve essere booleano");
                    }
                    var preview = publishing.prepareBatch(batchEntries, Boolean.TRUE.equals(args.get("start_next_week")));
                    var shorts = preview.drafts().stream().map(draft -> Map.of(
                            "position", draft.getBatchPosition(), "draftId", draft.getId(),
                            "filename", draft.getBatchFilename(), "title", draft.getTitle(),
                            "description", draft.getDescription(), "madeForKids", draft.isMadeForKids(),
                            "publishAtRome", draft.getPublishAt().atZone(ROME).toString(),
                            "slotEvidence", preview.slots().get(draft.getBatchPosition() - 1))).toList();
                    return ok(json, Map.of(
                            "batchId", preview.batchId(), "status", "DRAFT", "shorts", shorts,
                            "videosAnalyzed", preview.videosAnalyzed(), "historyTruncated", preview.historyTruncated(),
                            "playlistTitle", "Lo sapevi?", "confirmationText", "CONFERMO BATCH " + preview.batchId()));
                } catch (RuntimeException e) {
                    return failure(e);
                }
            }),
            new McpStatelessServerFeatures.SyncToolSpecification(scheduleBatchShortTool(), (context, request) -> {
                try {
                    requireWriteScope();
                    Map<String, Object> args = request.arguments();
                    String batchId = required(args, "batch_id");
                    if (!("CONFERMO BATCH " + batchId).equals(required(args, "confirmation_text"))) {
                        throw new IllegalArgumentException("Manca la conferma esplicita: CONFERMO BATCH " + batchId);
                    }
                    if (!(args.get("position") instanceof Number position)
                            || position.doubleValue() != position.intValue()
                            || position.intValue() < 1 || position.intValue() > BatchShortPlanner.MAX_BATCH_SIZE) {
                        throw new IllegalArgumentException("Posizione da 1 a 10 richiesta");
                    }
                    ShortDraft draft = publishing.getBatchDraft(batchId, position.intValue());
                    if (draft.getStatus() != ShortDraft.Status.DRAFT) {
                        return ok(json, batchStatus(draft));
                    }
                    OpenAiFileDownloadService.diagnoseAttachment("youtube_batch_short", args.get("file"));
                    var file = attachment(args.get("file"));
                    if (StringUtils.hasText(file.fileName()) && !draft.getBatchFilename().equals(file.fileName())) {
                        throw new IllegalArgumentException("L'allegato non corrisponde alla posizione approvata: "
                                + draft.getBatchFilename());
                    }
                    playlists.checkReady();
                    Path path = files.download(file);
                    try {
                        var video = new TemporaryVideoMultipart(path, "chatgpt-short.mp4");
                        ShortDraft result;
                        try {
                            result = publishing.scheduleBatch(draft, video, file.fileId());
                        } catch (RuntimeException scheduleError) {
                            ShortDraft saved = publishing.getBatchDraft(batchId, position.intValue());
                            if (saved.getStatus() == ShortDraft.Status.DRAFT) throw scheduleError;
                            return ok(json, batchStatus(saved));
                        }
                        Map<String, Object> payload = completedWithPlaylist(result, playlists);
                        payload.put("batchId", batchId);
                        payload.put("position", position.intValue());
                        return ok(json, payload);
                    } finally {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    }
                } catch (OpenAiFileDownloadService.AttachmentDownloadException e) {
                    return attachmentFailed(e);
                } catch (SecurityException e) {
                    return insufficientScope(mcpBaseUrl);
                } catch (RuntimeException e) {
                    return failure(e);
                }
            })
        );
    }

    private static McpSchema.Tool prepareBatchTool() {
        var itemSchema = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "filename", field("string", "Nome esatto del file MP4 allegato"),
                        "brief", field("string", "Riassunto o trascrizione di questo Short"),
                        "title", field("string", "Titolo fedele al video, massimo 100 caratteri"),
                        "description", field("string", "Descrizione con hashtag pertinenti"),
                        "made_for_kids", field("boolean", "Indica se questo contenuto è rivolto ai bambini")),
                "required", List.of("filename", "brief", "title", "description", "made_for_kids"));
        return McpSchema.Tool.builder()
                .name("chewbecca_prepare_shorts_batch")
                .title("Prepara fino a 10 Shorts")
                .description("""
                        Prepara una sola bozza per 1–10 Short MP4. Scrivi il titolo e la descrizione di
                        ogni video dal suo contenuto oppure chiedi il riassunto. Passa gli Shorts nell'ordine
                        di uscita desiderato. Pianifica fino a tre Shorts per settimana italiana, da lunedì
                        a domenica, contando gli Shorts già pubblicati e programmati, e salta interamente le
                        giornate che hanno altre uscite. Cerca l'orario con lo storico degli ultimi quattro mesi.
                        Distribuisci le uscite nelle prime settimane disponibili, partendo dalla corrente salvo
                        start_next_week=true. Mostra tutte le associazioni file/data, i titoli, le descrizioni,
                        il pubblico e la playlist Lo sapevi?. Aspetta CONFERMO BATCH <batchId> prima di caricare.
                        """)
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("shorts", Map.of("type", "array", "minItems", 1, "maxItems", 10, "items", itemSchema),
                                "start_next_week", field("boolean", "Inizia dal prossimo lunedì quando true")),
                        List.of("shorts"), false, null, null))
                .annotations(new McpSchema.ToolAnnotations("Prepara batch", false, false, false, false, false))
                .meta(READ_SECURITY)
                .build();
    }

    private static McpSchema.Tool scheduleBatchShortTool() {
        return McpSchema.Tool.builder()
                .name("chewbecca_schedule_batch_short")
                .title("Programma un file del batch confermato")
                .description("""
                        Scrive su YouTube. Dopo CONFERMO BATCH <batchId>, chiama una volta per posizione,
                        in sequenza, usando lo stesso testo di conferma per tutte le posizioni approvate.
                        Allega proprio il file MP4 della posizione indicata nella bozza. Rilegge giorno e
                        settimana prima dell'upload. Mostra l'esito di ogni posizione e i videoId disponibili.
                        Se una posizione è bloccata dal calendario, chiedi conferma per ripianificarla.
                        Se mostra UNKNOWN o UPLOADING, verifica lo stato prima di riprovare. Se lo Short è
                        SCHEDULED con NEEDS_RETRY, usa il recupero playlist separato con il suo draftId.
                        """)
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("batch_id", field("string", "ID del batch approvato"),
                                "position", Map.of("type", "integer", "minimum", 1, "maximum", 10),
                                "confirmation_text", field("string", "CONFERMO BATCH <batchId>, scritto dall'utente"),
                                "file", Map.of("$ref", "#/$defs/OpenAIFile")),
                        List.of("batch_id", "position", "confirmation_text", "file"), false,
                        Map.of("OpenAIFile", openAiFileSchema()), null))
                .annotations(new McpSchema.ToolAnnotations("Programma Short del batch", false, false, true, true, false))
                .meta(Map.of("openai/fileParams", List.of("file"),
                        "securitySchemes", WRITE_SECURITY.get("securitySchemes")))
                .build();
    }

    private static Map<String, Object> batchStatus(ShortDraft draft) {
        Map<String, Object> status = new LinkedHashMap<>(Map.of(
                "batchId", draft.getBatchId(), "position", draft.getBatchPosition(),
                "draftId", draft.getId(), "status", draft.getStatus().name(),
                "publishAtRome", draft.getPublishAt().atZone(ROME).toString()));
        if (draft.getYoutubeVideoId() != null) {
            status.put("videoId", draft.getYoutubeVideoId());
            status.put("message", "Video già programmato. Se necessario, verifica la playlist con lo strumento di recupero.");
        } else {
            status.put("message", "Verifica il canale prima di tentare un nuovo caricamento per questa posizione.");
        }
        return status;
    }

    private static Map<String, Object> completedWithPlaylist(ShortDraft result, YouTubePlaylistService playlists) {
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(Map.of("draftId", result.getId(), "status", result.getStatus().name(),
                "videoId", result.getYoutubeVideoId(),
                "youtubeUrl", "https://www.youtube.com/watch?v=" + result.getYoutubeVideoId(),
                "publishAtRome", result.getPublishAt().atZone(ROME).toString()));
        try {
            var assignment = playlists.ensureInTargetPlaylist(result.getYoutubeVideoId());
            payload.put("playlistTitle", assignment.playlistTitle());
            payload.put("playlistStatus", assignment.status());
            payload.put("message", "Short programmato e presente nella playlist " + assignment.playlistTitle());
        } catch (RuntimeException playlistError) {
            payload.put("playlistTitle", "Lo sapevi?");
            payload.put("playlistStatus", "NEEDS_RETRY");
            payload.put("message", "Video già programmato. Dopo conferma usa chewbecca_repair_short_playlist "
                    + "con il medesimo draftId per completare l'inserimento in playlist.");
        }
        return payload;
    }

    private static OpenAiFileDownloadService.FileInput attachment(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Allega un file MP4 alla chiamata di programmazione");
        }
        return new OpenAiFileDownloadService.FileInput(
                string(raw.get("download_url")), string(raw.get("file_id")),
                string(raw.get("mime_type")), string(raw.get("file_name")));
    }

    private static McpSchema.Tool prepareTool() {
        var props = Map.<String, Object>of(
                "brief", field("string", "Riassunto o trascrizione del video, min 30 caratteri. Non inventare fatti."),
                "title", field("string", "Titolo accattivante e veritiero per lo Short, max 100 caratteri."),
                "description", field("string", "Descrizione efficace e veritiera, max 5000 caratteri, hashtag pertinenti."),
                "publish_at", field("string", "Data e ora ISO 8601 con offset: es. 2026-12-10T19:30:00+01:00, Italia."),
                "made_for_kids", field("boolean", "Obbligatorio: è un video rivolto specificamente ai bambini?"),
                "weekly_plan", field("boolean", "True per il flusso della settimana corrente con controllo del calendario e playlist Lo sapevi?. Omettere per una normale programmazione."));
        return McpSchema.Tool.builder()
                .name("chewbecca_prepare_short")
                .title("Prepara Short YouTube")
                .description("CREA SOLO UNA BOZZA (nessun upload). Da un breve riassunto o trascrizione genera tramite ChatGPT titolo efficace ma veritiero e descrizione pertinente. Per la serie Lo sapevi? metti nel titolo il dettaglio più curioso e verificato e anticipa nella descrizione il valore del video con pochi hashtag mirati. Usa la data/ora italiana scelta dall'utente, convertita in ISO 8601 con offset. Mostra anteprima e richiedi conferma esplicita 'CONFERMO <draftId>'. Se l'utente chiede programmazione nella settimana corrente senza sovrapposizioni e analisi 4 mesi, chiama PRIMA chewbecca_plan_weekly_short e usa il suo recommended.dateTime come publish_at con weekly_plan=true. made_for_kids=false solo se il video non e' destinato specificamente ai bambini. Se manca il contenuto del video, richiedi un riassunto: non dedurlo dal nome del file. Dopo conferma puoi utilizzare chewbecca_schedule_short con il file MP4 allegato.")
                .inputSchema(new McpSchema.JsonSchema("object", props,
                        List.of("brief", "title", "description", "publish_at", "made_for_kids"), false, null, null))
                .annotations(new McpSchema.ToolAnnotations("Prepara Short", false, false, false, false, false))
                .meta(READ_SECURITY)
                .build();
    }

    private static McpSchema.Tool scheduleTool() {
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
                        Richiede shorts:write. Per le bozze settimanali ricontrolla l'intera giornata italiana
                        e richiede lo scope Google youtube.force-ssl per la playlist Lo sapevi?.
                        Per la settimana corrente usa prima chewbecca_plan_weekly_short,
                        poi prepara la bozza con weekly_plan=true e l'orario proposto.
                        Mostra anche la playlist prevista prima di chiedere conferma.
                        Usa made_for_kids=false soltanto se il video non è rivolto ai bambini.
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
                        Map.of("OpenAIFile", openAiFileSchema()), null))
                .annotations(new McpSchema.ToolAnnotations("Programma Short", false, false, false, true, false))
                .meta(Map.of(
                        "openai/fileParams", List.of("file"),
                        "securitySchemes", WRITE_SECURITY.get("securitySchemes"),
                        "openai/toolInvocation/invoking", "Caricamento video su YouTube…",
                        "openai/toolInvocation/invoked", "Programmazione terminata"))
                .build();
    }

    private static Map<String, Object> openAiFileSchema() {
        return Map.of(
                "type", "object", "properties", Map.of(
                        "download_url", Map.of("type", "string"),
                        "file_id", Map.of("type", "string"),
                        "mime_type", Map.of("type", "string"),
                        "file_name", Map.of("type", "string")),
                "required", List.of("download_url", "file_id"),
                "additionalProperties", false);
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }
    private static String string(Object value) { return value instanceof String s ? s : null; }
    private static String required(Map<?, ?> args, String name) {
        String value = string(args.get(name));
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Campo obbligatorio: " + name);
        return value.trim();
    }
    private static boolean requiredBoolean(Map<?, ?> args, String name) {
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
