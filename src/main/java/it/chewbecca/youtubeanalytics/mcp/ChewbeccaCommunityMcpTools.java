package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import it.chewbecca.youtubeanalytics.community.CommunityManager;
import it.chewbecca.youtubeanalytics.community.CommunityReplyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.mcp.ChewbeccaStudioMcpSupport.*;
import static it.chewbecca.youtubeanalytics.mcp.ChewbeccaStudioMcpSupport.Mode.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.mcp.enabled", "app.community.enabled"}, havingValue = "true")
public class ChewbeccaCommunityMcpTools {
    @Bean
    List<SyncToolSpecification> chewbeccaCommunityTools(CommunityManager manager, ObjectMapper json,
                                                         @Value("${app.mcp.public-url}") String url) {
        var tool = new ChewbeccaStudioMcpSupport(json, url);
        var item = object(Map.of("thread_id", text(), "comment_id", text(), "reply", text(), "reason", text(),
                "priority", strings("LOW", "MEDIUM", "HIGH", "MANUAL", "SPAM", "INSULT")),
                List.of("thread_id", "comment_id"));
        var edit = object(Map.of("number", integer(), "action", strings("APPROVE", "EDIT", "EXCLUDE"), "reply", text()),
                List.of("number", "action"));
        return List.of(
                tool.tool("chewbecca_list_comments", DATA + """
                        Legge i commenti sui video lunghi e Shorts del canale, anche su video vecchi. Date italiane.
                        Mantieni periodo, moderazione e page_size a ogni continuazione. Itera nextPageToken fino a
                        esaurimento. Per una revisione completa usa anche heldForReview e likelySpam in scansioni
                        separate e riporta quei commenti per valutazione manuale. Deduplica per comment.id.
                        Una pagina vuota con token successivo non conclude la scansione. Scorri le pagine per trovare
                        risposte nuove a thread vecchi. Ignora i commenti con risposta del canale quando proponi risposte.
                        """, Map.of("period", strings("THIS_WEEK", "PREVIOUS_WEEK", "LAST_DAYS", "CUSTOM"),
                        "days", integer(), "from", text(), "to", text(), "page_token", text(), "page_size", integer(),
                        "moderation", strings("published", "heldForReview", "likelySpam")),
                        List.of(), READ, args -> out(manager.list(str(args, "period"), args.containsKey("days")
                                ? num(args, "days", 0) : null, date(args, "from"), date(args, "to"),
                                str(args, "page_token"), num(args, "page_size", 15), str(args, "moderation")))),
                tool.tool("chewbecca_comment_context", DATA + "Legge video, commento principale, risposte e stato del thread.",
                        Map.of("thread_id", text()), List.of("thread_id"), READ,
                        args -> out(manager.context(required(args, "thread_id")))),
                tool.tool("chewbecca_prepare_comment_replies", DATA + """
                        Registra al massimo 25 proposte generate in ChatGPT. Usa una risposta per thread, naturale,
                        competente e personalizzata in italiano, salvo lingua diversa del commento. Distingui Canon e
                        Legends, verifica fonti quando utile e riconosci possibili errori del canale. Segnala spam,
                        insulti e casi da valutare tramite le priorità apposite. Mostra in tabella numero, video,
                        autore, commento, proposta, motivazione e priorità, poi chiedi approvazione.
                        """, Map.of("proposals", array(item)), List.of("proposals"), DRAFT,
                        args -> out(preview(manager.prepare(list(args, "proposals").stream()
                                .map(ChewbeccaStudioMcpSupport::row).map(value -> new CommunityManager.Proposal(
                                        required(value, "thread_id"), required(value, "comment_id"), str(value, "reply"),
                                        str(value, "priority"), str(value, "reason"))).toList())))),
                tool.tool("chewbecca_preview_comment_replies", DATA + "Legge la revisione e lo stato di tutte le righe.",
                        Map.of("batch_id", text()), List.of("batch_id"), READ,
                        args -> out(preview(manager.preview(required(args, "batch_id"))))),
                tool.tool("chewbecca_update_comment_replies", APPROVAL_NOTICE + """
                        Modifica o esclude singole righe, oppure registra l'approvazione di tutte le bozze o dei numeri
                        indicati. Mostra la nuova revisione. Una modifica alla risposta richiede una nuova approvazione.
                        """, Map.of("batch_id", text(), "revision", integer(), "edits", array(edit), "approve_all", bool(),
                        "confirmation_text", text()), List.of("batch_id", "revision"), APPROVAL, args -> out(preview(
                        manager.update(required(args, "batch_id"), num(args, "revision", 0),
                                list(args, "edits").stream().map(ChewbeccaStudioMcpSupport::row)
                                        .map(value -> new CommunityReplyRepository.Edit(num(value, "number", 0),
                                                required(value, "action"), str(value, "reply"))).toList(),
                                yes(args, "approve_all"), str(args, "confirmation_text"))))),
                tool.tool("chewbecca_publish_comment_replies", APPROVAL_NOTICE + """
                        Pubblica su YouTube soltanto le risposte approvate della revisione confermata. Chiede due
                        azioni distinte: approvazione della proposta e pubblicazione. Una ripetizione verifica gli esiti incerti prima di qualsiasi nuovo invio.
                        """, Map.of("batch_id", text(), "revision", integer(), "confirmation_text", text(),
                        "dry_run", bool()), List.of("batch_id", "revision", "confirmation_text"), WRITE,
                        args -> out(manager.publish(required(args, "batch_id"), num(args, "revision", 0),
                                required(args, "confirmation_text"), yes(args, "dry_run")))),
                tool.tool("chewbecca_comment_batch_status", "Mostra l'esito di ogni riga del batch e la revisione corrente.",
                        Map.of("batch_id", text()), List.of("batch_id"), READ,
                        args -> out(preview(manager.preview(required(args, "batch_id"))))),
                tool.tool("chewbecca_comment_insights", "Conta le risposte registrate nei batch correnti per stato.",
                        Map.of(), List.of(), READ, args -> out(manager.insights()))
        );
    }

    private Map<String, Object> preview(CommunityReplyRepository.Batch batch) {
        var status = batch.items().stream().collect(java.util.stream.Collectors.groupingBy(
                CommunityReplyRepository.Item::status, java.util.stream.Collectors.counting()));
        return Map.of("batchId", batch.id(), "revision", batch.revision(), "createdAt", batch.createdAt(),
                "items", batch.items(), "statusCounts", status,
                "approveConfirmation", CommunityManager.approval(batch.id(), batch.revision()),
                "publishConfirmation", batch.confirmation(), "handling", DATA);
    }
}
