package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.performance.PerformanceAdvisor;
import it.chewbecca.youtubeanalytics.performance.ReachCollector;
import it.chewbecca.youtubeanalytics.performance.StudioWeeklyReport;
import it.chewbecca.youtubeanalytics.performance.VideoOptimizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.mcp.ChewbeccaStudioMcpSupport.*;
import static it.chewbecca.youtubeanalytics.mcp.ChewbeccaStudioMcpSupport.Mode.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.mcp.enabled", "app.performance.enabled"}, havingValue = "true")
public class ChewbeccaPerformanceMcpTools {
    @Bean
    List<SyncToolSpecification> chewbeccaPerformanceTools(PerformanceAdvisor advisor, VideoOptimizationService edits,
                                                           ReachCollector reporting, StudioWeeklyReport report,
                                                           OpenAiFileDownloadService files, ObjectMapper json,
                                                           @Value("${app.mcp.public-url}") String url) {
        var tool = new ChewbeccaStudioMcpSupport(json, url);
        Map<String, Object> video = Map.of("video_id", text());
        Map<String, Object> age = Map.of("video_id", text(), "age_days", integer());
        var allowedSections = strings("CORE", "ENGAGEMENT", "DAILY", "REACH", "TRAFFIC", "SEARCH_TERMS",
                "SUGGESTED_VIDEOS", "EXTERNAL_DETAILS", "RETENTION", "ENGAGED_VIEWS", "END_SCREENS", "MONETARY", "ADS");
        return List.of(
                tool.tool("chewbecca_video_metrics", DATA + """
                        Dati ufficiali YouTube per video. Usa sections per reach, retention, traffico, ricerca,
                        engagement, Shorts, schermate finali o ricavi. Leggi disponibilità, finestra e aggiornamento.
                        Mostra assenze e dati parziali. Non confondere CTR miniature con impressioni pubblicitarie.
                        """, Map.of("video_id", text(), "from", text(), "to", text(), "sections", array(allowedSections)),
                        List.of("video_id"), READ, args -> out(advisor.metrics(required(args, "video_id"),
                                date(args, "from"), date(args, "to"), strings(args, "sections")))),
                tool.tool("chewbecca_video_benchmark", DATA + """
                        Confronta con contenuti del canale alla stessa età. age_days: 1, 2, 7 o 28 giorni di calendario.
                        Mostra mediane, percentili, numero di comparabili, finestra e confidenza per i dati disponibili.
                        """, age, List.of("video_id"), READ,
                        args -> out(advisor.benchmark(required(args, "video_id"), num(args, "age_days", 7)))),
                tool.tool("chewbecca_analyze_video_performance", DATA + """
                        Restituisce ipotesi numeriche A–E. Mostra titolo, copertina, confronto, priorità e fattori
                        alternativi. Interpreta possibili incoerenze tra promessa e contenuto con il contesto.
                        Usa 1, 2, 7 o 28 giorni di calendario. Non affermare un rapporto causale.
                        """, age, List.of("video_id"), READ,
                        args -> out(advisor.analyze(required(args, "video_id"), num(args, "age_days", 7)))),
                tool.tool("chewbecca_audit_channel_performance", DATA + """
                        Esamina tutti gli upload pubblicati nel periodo per formato VIDEO o SHORT e pagina la graduatoria.
                        Controlla hasNext. Usa l'analisi del video per confermare o scartare gli interventi prioritari.
                        """, Map.of("format", strings("VIDEO", "SHORT"), "from", text(), "to", text(),
                        "page", integer(), "limit", integer()), List.of("format", "from", "to"), READ,
                        args -> out(advisor.audit(required(args, "format"), date(args, "from"), date(args, "to"),
                                num(args, "page", 1), num(args, "limit", 20), edits.cooling()))),
                tool.tool("chewbecca_optimization_context", DATA + """
                        Fornisce la descrizione, lo stile delle copertine e, quando disponibile, il transcript
                        del video lungo. Per le miniature riusa i riferimenti grafici esistenti del canale e
                        genera in ChatGPT tre proposte distinte. Usa solo dettagli pertinenti al video.
                        """, Map.of("video_id", text(), "with_caption", bool()), List.of("video_id"), READ,
                        args -> out(edits.context(required(args, "video_id"), !Boolean.FALSE.equals(args.get("with_caption"))))),
                tool.tool("chewbecca_prepare_video_optimization", DATA + """
                        Registra una sola azione per bozza: tre titoli alternativi fedeli (max 100 caratteri),
                        una descrizione o tre miniature orizzontali A/B/C di un video lungo. Mostra opzioni e
                        motivazione e chiedi l'approvazione separata per ciascun intervento che verrà applicato.
                        """, Map.of("video_id", text(), "action", strings("TITLE", "DESCRIPTION", "THUMBNAIL"),
                        "reason", text(), "options", array(text()), "option_a", FILE_REF, "option_b", FILE_REF,
                        "option_c", FILE_REF), List.of("video_id", "action", "reason"), DRAFT, true, args -> {
                    String action = required(args, "action");
                    var images = new ArrayList<byte[]>();
                    if (action.equals("THUMBNAIL")) {
                        for (String key : FILE_KEYS) images.add(download(files, args, key));
                    }
                    return new Output(edits.prepare(required(args, "video_id"), action, required(args, "reason"),
                            strings(args, "options"), images), images);
                }),
                tool.tool("chewbecca_preview_video_optimization", DATA + "Rileggi le proposte, lo stato e la conferma specifica per lettera.",
                        Map.of("optimization_id", text()), List.of("optimization_id"), READ,
                        args -> out(edits.preview(required(args, "optimization_id")))),
                tool.tool("chewbecca_apply_video_optimization", APPROVAL_NOTICE + """
                        Aggiorna esclusivamente l'intervento scelto. Verifica proprietà e valore attuale.
                        Rispetta dry-run e non ripete automaticamente una modifica dall'esito incerto.
                        """, Map.of("optimization_id", text(), "revision", integer(), "choice", strings("A", "B", "C"),
                        "confirmation_text", text(), "dry_run", bool()),
                        List.of("optimization_id", "revision", "choice", "confirmation_text"), WRITE,
                        args -> out(edits.apply(required(args, "optimization_id"), num(args, "revision", 0),
                                required(args, "choice"), required(args, "confirmation_text"), yes(args, "dry_run")))),
                tool.tool("chewbecca_optimization_history", DATA + "Elenca interventi, metadati documentati e misure precedenti e successive.",
                        video, List.of("video_id"), READ, args -> out(edits.history(required(args, "video_id")))),
                tool.tool("chewbecca_evaluate_optimization", """
                        Confronta 7, 14 o 28 giorni prima e dopo, escludendo il giorno dell'intervento. La raccolta
                        aggiorna la valutazione locale e non scrive su YouTube. Controlla disponibilità e fattori
                        concomitanti. Presenta i risultati come osservazioni.
                        """, Map.of("optimization_id", text(), "days", integer(), "refresh", bool()),
                        List.of("optimization_id", "days"), DRAFT,
                        args -> out(edits.evaluate(required(args, "optimization_id"), num(args, "days", 0), yes(args, "refresh")))),
                tool.tool("chewbecca_weekly_performance_report", DATA + """
                        Raccoglie dati di performance, commenti da esaminare e rivalutazioni della settimana precedente.
                        Completa le pagine dei commenti se la copertura è parziale. Elabora proposte editoriali in
                        ChatGPT e chiedi approvazione per ogni scrittura esterna.
                        """, Map.of("comment_pages", integer()), List.of(), DRAFT,
                        args -> out(report.report(num(args, "comment_pages", 3)))),
                tool.tool("chewbecca_reporting_status", "Stato del reporting job reach e ultimo tentativo locale.",
                        Map.of(), List.of(), READ, args -> out(reporting.status(advisor.channel())))
        );
    }
}
