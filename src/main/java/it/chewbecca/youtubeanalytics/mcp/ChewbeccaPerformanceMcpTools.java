package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.performance.AdvisorMetrics;
import it.chewbecca.youtubeanalytics.performance.ExtendedReportingCollector;
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
                                                           ReachCollector reporting, ExtendedReportingCollector extendedReporting,
                                                           StudioWeeklyReport report,
                                                           OpenAiFileDownloadService files, ObjectMapper json,
                                                           @Value("${app.mcp.public-url}") String url) {
        var tool = new ChewbeccaStudioMcpSupport(json, url);
        Map<String, Object> video = Map.of("video_id", text());
        Map<String, Object> age = Map.of("video_id", text(), "age_days", integer());
        var allowedSections = strings(AdvisorMetrics.sections(false).toArray(String[]::new));
        var channelSections = strings(AdvisorMetrics.sections(true).toArray(String[]::new));
        return List.of(
                tool.tool("chewbecca_video_metrics", DATA + """
                        Dati ufficiali YouTube per video. Usa sections per reach, retention, traffico, ricerca,
                        engagement, Shorts, schermate finali o ricavi. Per un'analisi estesa usa pochi gruppi
                        di sezioni per richiesta. Leggi disponibilità, finestra e aggiornamento. Mostra dati assenti
                        o parziali. Distingui CTR delle miniature, visualizzazioni nel feed e impressioni pubblicitarie.
                        """, Map.of("video_id", text(), "from", text(), "to", text(), "sections", array(allowedSections)),
                        List.of("video_id"), READ, args -> out(advisor.metrics(required(args, "video_id"),
                                date(args, "from"), date(args, "to"), strings(args, "sections")))),
                tool.tool("chewbecca_channel_metrics", DATA + """
                        Metriche aggregate del canale collegato. Seleziona sezioni come DEMOGRAPHICS, COUNTRY,
                        SUBSCRIPTION_STATUS, SHORTS_FEED, REACH_BY_SOURCE o MONETARY. Verifica il periodo osservato
                        e la copertura. I dati aggregati Reporting coprono gli upload attribuibili al canale.
                        """, Map.of("from", text(), "to", text(), "sections", array(channelSections)), List.of(), READ,
                        args -> out(advisor.channelMetrics(date(args, "from"), date(args, "to"), strings(args, "sections")))),
                tool.tool("chewbecca_video_benchmark", DATA + """
                        Confronta con contenuti del canale alla stessa età. age_days: 1, 2, 7 o 28 giorni di calendario.
                        Mostra mediane, percentili, numero di comparabili, finestra e confidenza per i dati disponibili.
                        """, age, List.of("video_id"), READ,
                        args -> out(advisor.benchmark(required(args, "video_id"), num(args, "age_days", 7)))),
                tool.tool("chewbecca_analyze_video_performance", DATA + """
                        Restituisce ipotesi numeriche A–E. Mostra titolo, copertina, confronto, priorità e fattori
                        alternativi. Interpreta possibili incoerenze tra promessa e contenuto con il contesto.
                        Prima di suggerire un nuovo intervento, leggi lo storico incluso e verifica se quello precedente
                        ha dati maturi. Usa la nota editoriale come contesto, non come una metrica di YouTube.
                        Usa 1, 2, 7 o 28 giorni di calendario. Non affermare un rapporto causale.
                        """, age, List.of("video_id"), READ, args -> {
                            String videoId = required(args, "video_id");
                            ObjectNode diagnosis = json.valueToTree(advisor.analyze(videoId, num(args, "age_days", 7)));
                            diagnosis.set("editorialHistory", json.valueToTree(edits.editorialHistory(videoId)));
                            return out(diagnosis);
                        }),
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
                        genera in ChatGPT tre proposte distinte. Usa solo dettagli pertinenti al video e considera
                        gli interventi già provati e le note editoriali prima di proporre un nuovo cambiamento.
                        """, Map.of("video_id", text(), "with_caption", bool()), List.of("video_id"), READ, args -> {
                            String videoId = required(args, "video_id");
                            ObjectNode context = json.valueToTree(edits.context(videoId,
                                    !Boolean.FALSE.equals(args.get("with_caption"))));
                            context.set("editorialHistory", json.valueToTree(edits.editorialHistory(videoId)));
                            return out(context);
                        }),
                tool.tool("chewbecca_prepare_video_optimization", DATA + """
                        Prima di preparare una proposta, consulta lo storico in chewbecca_optimization_context.
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
                        video, List.of("video_id"), READ, args -> {
                            String videoId = required(args, "video_id");
                            return out(Map.of("items", edits.history(videoId),
                                    "editorialHistory", edits.editorialHistory(videoId)));
                        }),
                tool.tool("chewbecca_review_recent_optimizations", DATA + """
                        Riesamina le modifiche applicate negli ultimi 30 giorni, per video e giorno. Confronta la
                        finestra più lunga disponibile tra 7, 14 e 28 giorni, escludendo il giorno dell'intervento,
                        e segnala altri interventi che si sovrappongono alle finestre. Mostra anche i confronti in
                        attesa. Interpreta impressioni, CTR, views e watch time in chat. Se ci sono modifiche
                        ravvicinate, attribuisci l'andamento al periodo e non a una singola azione.
                        """, Map.of("page", integer(), "limit", integer(), "refresh", bool()), List.of(), DRAFT,
                        args -> out(edits.reviewRecent(num(args, "page", 1), num(args, "limit", 4), yes(args, "refresh")))),
                tool.tool("chewbecca_record_optimization_note", DATA + """
                        Memorizza nel database una nota editoriale dell'utente su un intervento applicato. Usa
                        solo un testo mostrato e approvato dall'utente. La nota dura oltre la finestra dei dati
                        temporanei. Non includere dati grezzi di YouTube. Non modifica il video.
                        """, Map.of("optimization_id", text(), "note", text()), List.of("optimization_id", "note"),
                        APPROVAL, args -> {
                            String id = required(args, "optimization_id");
                            edits.recordEditorialNote(id, required(args, "note"));
                            return out(Map.of("optimizationId", id, "status", "SAVED"));
                        }),
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
                tool.tool("chewbecca_reporting_status", "Stato dei reporting job reach e dei report estesi.",
                        Map.of(), List.of(), READ, args -> {
                            String channel = advisor.channel();
                            ObjectNode status = json.valueToTree(reporting.status(channel));
                            status.set("extendedReports", json.valueToTree(extendedReporting.status(channel)));
                            return out(status);
                        })
        );
    }
}
