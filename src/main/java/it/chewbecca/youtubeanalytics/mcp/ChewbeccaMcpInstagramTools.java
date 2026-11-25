package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.instagram.InstagramBridgeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.mcp.enabled:false} && ${app.instagram.enabled:false}")
public class ChewbeccaMcpInstagramTools {
    private static final String WRITE = "shorts:write";
    private static final String READ = "shorts:read";
    private static final Map<String, Object> ID = text("ID bozza Instagram");
    private static final Map<String, Object> CONFIRM = text("Conferma esplicita dell'utente");

    @Bean
    InstagramBridgeClient instagramBridgeClient(@Value("${app.instagram.url}") String url,
                                               @Value("${app.instagram.token}") String token) {
        return new InstagramBridgeClient(url, token);
    }

    @Bean
    List<SyncToolSpecification> chewbeccaInstagramTools(InstagramBridgeClient bridge, OpenAiFileDownloadService files,
                                                       ObjectMapper json, @Value("${app.mcp.public-url}") String baseUrl) {
        Map<String, Object> fileDef = Map.of("OpenAIFile", Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("download_url", text("URL temporaneo ChatGPT"), "file_id", text("ID allegato"),
                        "mime_type", text("Tipo JPG/PNG"), "file_name", text("Nome allegato")),
                "required", List.of("download_url", "file_id")));
        var prepare = descriptor("chewbecca_prepare_instagram_story", "Prepara storia Instagram", """
                Registra una immagine JPG/PNG verticale 9:16 già approvabile. Non pubblica. Per LINK passa l'URL YouTube,
                per POLL domanda e 2-4 opzioni, per PLAIN nessuno sticker (es. soluzioni). Mantieni spazio libero per
                lo sticker: centro x=50%, y=76% link o y=67% poll. Mostra immagine, dati e ID bozza. Non inventare file.
                Se l'immagine generata non è disponibile al tool, chiedi di allegarla. Per una rubrica prepara 5 bozze POLL
                e una PLAIN con le soluzioni; ripeti per ogni ciclo e non duplicare le domande. Non pubblicare senza conferma.
                """, Map.of("image", Map.of("$ref", "#/$defs/OpenAIFile"), "kind", Map.of("type", "string", "enum", List.of("LINK", "POLL", "PLAIN")),
                "link", text("URL HTTPS YouTube"), "question", text("Domanda del sondaggio"),
                "options", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 2, "maxItems", 4)),
                List.of("image", "kind"), false, false, fileDef, true);
        return List.of(
                bind(descriptor("chewbecca_instagram_quiz_history", "Storico domande Instagram", "Leggi prima di generare quiz; evita ripetizioni anche semantiche. Le domande già preparate e quelle pubblicate sono incluse.", Map.of(), List.of(), true, false, null, false), false, baseUrl, json, args -> bridge.quizHistory()),
                bind(prepare, true, baseUrl, json, args -> {
                    if (!(args.get("image") instanceof Map<?, ?> file)) throw new IllegalArgumentException("Allega un JPG o PNG verticale");
                    var input = new OpenAiFileDownloadService.FileInput(str(file.get("download_url")), str(file.get("file_id")),
                            str(file.get("mime_type")), str(file.get("file_name")));
                    var path = files.downloadImage(input);
                    try {
                        if (Files.size(path) > 12L * 1024 * 1024) throw new IllegalArgumentException("Immagine oltre 12 MB");
                        return bridge.prepare(Files.readAllBytes(path), required(args,"kind"), optional(args,"link"),
                                optional(args,"question"), json.writeValueAsString(args.getOrDefault("options", List.of())));
                    } finally { Files.deleteIfExists(path); }
                }),
                bind(descriptor("chewbecca_instagram_story_status", "Stato storia Instagram", "Legge stato e ID della storia. UNKNOWN e PUBLISHING richiedono verifica manuale. PUBLISHED non verifica l'interattività dello sticker.", Map.of("draft_id", ID), List.of("draft_id"), true, false, null, false), false, baseUrl, json,
                        args -> bridge.status(UUID.fromString(required(args,"draft_id")))),
                bind(descriptor("chewbecca_publish_instagram_story", "Pubblica storia Instagram", "Scrive su Instagram. Solo dopo anteprima e conferma esplicita CONFERMO STORY <draft_id>. Mai ripetere se esito incerto. Verifica manualmente lo sticker.", Map.of("draft_id", ID, "confirmation_text", CONFIRM), List.of("draft_id","confirmation_text"), false, true, null, false), true, baseUrl, json,
                        args -> bridge.command(UUID.fromString(required(args,"draft_id")), "publish", Map.of("confirmationText", required(args,"confirmation_text")))),
                bind(descriptor("chewbecca_schedule_instagram_story", "Programma storia Instagram", """
                        Programma una bozza già mostrata. Usa data ISO 8601 con secondi e offset Europe/Rome, es. 2026-12-01T10:00:00+01:00.
                        Richiede conferma esplicita CONFERMO STORY <draft_id> <publish_at> con lo stesso formato. Ogni storia ha conferma separata.
                        Per quiz pianifica 5 domande con orari distinti (almeno un minuto di distanza) e soluzioni il giorno successivo;
                        ripeti a intervalli di 5 giorni. Non pianificare soluzioni prima delle domande. Nessuna pubblicazione in DRY_RUN.
                        """, Map.of("draft_id", ID, "publish_at", text("Data ISO 8601 con secondi e offset"), "confirmation_text", CONFIRM), List.of("draft_id","publish_at","confirmation_text"), false, true, null, false), true, baseUrl, json,
                        args -> bridge.command(UUID.fromString(required(args,"draft_id")), "schedule", Map.of("confirmationText", required(args,"confirmation_text"), "publishAt", required(args,"publish_at")))),
                bind(descriptor("chewbecca_cancel_instagram_story", "Annulla storia Instagram", "Annulla una bozza o programmazione non ancora pubblicata. Richiede ANNULLA STORY <draft_id>. Non cancella storie già pubblicate.", Map.of("draft_id", ID, "confirmation_text", CONFIRM), List.of("draft_id","confirmation_text"), false, true, null, false), true, baseUrl, json,
                        args -> bridge.command(UUID.fromString(required(args,"draft_id")), "cancel", Map.of("confirmationText", required(args,"confirmation_text"))))
        );
    }

    private static McpSchema.Tool descriptor(String name, String title, String description, Map<String,Object> fields,
                                             List<String> required, boolean readOnly, boolean destructive,
                                             Map<String,Object> defs, boolean attachment) {
        var security = List.of(Map.of("type", "oauth2", "scopes", readOnly ? List.of(READ) : List.of(READ,WRITE)));
        Map<String,Object> meta = attachment ? Map.of("securitySchemes", security, "openai/fileParams", List.of("image")) : Map.of("securitySchemes", security);
        return McpSchema.Tool.builder().name(name).title(title).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", fields, required, false, defs, null))
                .annotations(new McpSchema.ToolAnnotations(title, readOnly, destructive, readOnly, true, false)).meta(meta).build();
    }

    @FunctionalInterface private interface Action { Object run(Map<String,Object> args) throws IOException; }

    private static SyncToolSpecification bind(McpSchema.Tool spec, boolean write, String baseUrl, ObjectMapper json, Action action) {
        return new SyncToolSpecification(spec, (context, request) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> ("SCOPE_"+READ).equals(a.getAuthority())) ||
                    (write && auth.getAuthorities().stream().noneMatch(a -> ("SCOPE_"+WRITE).equals(a.getAuthority())))) {
                String metadata = baseUrl.replaceAll("/+$", "") + "/.well-known/oauth-protected-resource";
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Riconnetti Chewbecca Studio con i permessi richiesti.")), true, null,
                        Map.of("mcp/www_authenticate", List.of("Bearer resource_metadata=\"" + metadata + "\", scope=\"shorts:read" + (write ? " shorts:write" : "") + "\", error=\"insufficient_scope\"")));
            }
            try {
                Object result = action.run(request.arguments());
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json.writeValueAsString(result))), false, json.valueToTree(result), null);
            } catch (RuntimeException | IOException ex) {
                String msg = ex instanceof OpenAiFileDownloadService.AttachmentDownloadException || ex instanceof IllegalArgumentException || ex instanceof IllegalStateException ? ex.getMessage() : "Operazione Instagram fallita; verifica lo stato prima di riprovare";
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(msg == null ? "Errore Instagram" : msg)), true);
            }
        });
    }

    private static Map<String,Object> text(String description) { return Map.of("type","string","description",description); }
    private static String str(Object v) { return v instanceof String s ? s : null; }
    private static String optional(Map<String,Object> a,String k) { String s = str(a.get(k)); return s == null ? "" : s; }
    private static String required(Map<String,Object> a,String k) { String s=optional(a,k); if(s.isBlank()) throw new IllegalArgumentException("Parametro mancante: "+k); return s.trim(); }
}
