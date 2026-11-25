package it.chewbecca.youtubeanalytics.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.thumbnail.ThumbnailService;
import it.chewbecca.youtubeanalytics.thumbnail.YouTubeThumbnailClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static it.chewbecca.youtubeanalytics.performance.PerformanceAdvisor.Window;
import static it.chewbecca.youtubeanalytics.performance.VideoOptimizationRepository.Draft;

@Service
public class VideoOptimizationService {
    private static final Logger log = LoggerFactory.getLogger(VideoOptimizationService.class);
    private static final List<String> CHOICES = List.of("A", "B", "C");
    private final VideoOptimizationRepository repository;
    private final PerformanceAdvisor advisor;
    private final YouTubeVideoChanges changes;
    private final ThumbnailService thumbnailContext;
    private final YouTubeThumbnailClient thumbnails;
    private final GoogleAccessTokenService tokens;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final boolean dryRun;

    public VideoOptimizationService(VideoOptimizationRepository repository, PerformanceAdvisor advisor,
                                    YouTubeVideoChanges changes, ThumbnailService thumbnailContext,
                                    YouTubeThumbnailClient thumbnails, GoogleAccessTokenService tokens,
                                    ObjectMapper json, Clock clock, MeterRegistry meters,
                                    @Value("${app.performance.dry-run:true}") boolean dryRun) {
        this.repository = repository;
        this.advisor = advisor;
        this.changes = changes;
        this.thumbnailContext = thumbnailContext;
        this.thumbnails = thumbnails;
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.dryRun = dryRun;
    }

    public Object context(String id, boolean withCaption) {
        var video = advisor.owned(id);
        if (advisor.format(video).equals("VIDEO")) return thumbnailContext.context(id, withCaption);
        return Map.of("videoId", id, "title", video.title(), "description", video.description(),
                "thumbnail", "Usa le opzioni di modifica della miniatura Shorts messe a disposizione da YouTube.");
    }

    public Preview prepare(String videoId, String action, String reason, List<String> options, List<byte[]> images) {
        if (!List.of("TITLE", "DESCRIPTION", "THUMBNAIL").contains(action) || reason == null || reason.isBlank()
                || reason.length() > 2000) throw new IllegalArgumentException("Indica un intervento e una motivazione valida");
        var video = advisor.owned(videoId);
        if (action.equals("THUMBNAIL")) {
            if (!advisor.format(video).equals("VIDEO")) throw new IllegalArgumentException("Miniature caricate solo per video lunghi");
            thumbnailContext.context(videoId, false);
            if (images.size() != 3 || images.stream().map(VideoOptimizationService::hash).distinct().count() != 3) {
                throw new IllegalArgumentException("Allega tre copertine diverse");
            }
            options = images.stream().map(image -> "SHA-256 " + hash(image)).toList();
        } else {
            int count = action.equals("TITLE") ? 3 : 1;
            int length = action.equals("TITLE") ? 100 : 5000;
            if (options.size() != count || options.stream().distinct().count() != count
                    || options.stream().anyMatch(value -> value.isBlank() || value.contains("<") || value.contains(">")
                    || (action.equals("TITLE") ? value.codePointCount(0, value.length())
                    : value.getBytes(StandardCharsets.UTF_8).length) > length)) {
                throw new IllegalArgumentException("Fornisci tre titoli diversi da 1 a 100 caratteri o una descrizione entro 5000 byte");
            }
        }
        String channel = advisor.channel();
        var current = changes.read(channel, videoId);
        String previous = current.value(action);
        if (options.contains(previous)) throw new IllegalArgumentException("Le alternative devono cambiare il valore attuale");
        var draft = repository.create(new Draft(UUID.randomUUID().toString(), channel, videoId, action,
                "READY", 1, reason, previous, null, clock.instant(), null, null, encode(options)), images);
        return preview(draft);
    }

    public Preview preview(String id) { return preview(repository.draft(advisor.channel(), id)); }

    public ApplyResult apply(String id, int revision, String choice, String confirmation, boolean simulate) {
        String owner = advisor.channel();
        var draft = repository.draft(owner, id);
        var options = preview(draft).options();
        int index = CHOICES.indexOf(choice);
        if (revision != draft.revision() || index < 0 || index >= options.size()
                || !confirmation(draft, choice).equals(confirmation)) {
            throw new IllegalArgumentException("Conferma la lettera e la revisione mostrate per questo intervento");
        }
        String selected = options.get(index);
        boolean simulation = simulate || dryRun;
        if (draft.state().equals("APPLIED")) {
            if (!Objects.equals(draft.applied(), selected)) throw new IllegalStateException("È stata applicata un'altra opzione");
            return result(draft, "ALREADY_APPLIED", simulation);
        }
        if (List.of("UNKNOWN", "APPLYING").contains(draft.state())) {
            if (!draft.action().equals("THUMBNAIL") && Objects.equals(selected, draft.applied())
                    && selected.equals(changes.read(owner, draft.videoId()).value(draft.action()))) {
                if (!simulation) repository.finish(draft, "APPLIED", selected, draft.appliedAt(), null, true);
                return result(draft, simulation ? "WOULD_RECONCILE" : "RECONCILED", simulation);
            }
            return result(draft, "UNKNOWN_REQUIRES_REVIEW", simulation);
        }
        if (!draft.state().equals("READY")) return result(draft, draft.state(), simulation);
        var current = changes.read(owner, draft.videoId());
        if (!Objects.equals(current.value(draft.action()), draft.previous())) return result(draft, "STALE", simulation);
        if (repository.applied(owner, clock.instant().minus(Duration.ofDays(7))).stream()
                .anyMatch(v -> v.videoId().equals(draft.videoId()) && v.action().equals(draft.action()))) {
            return result(draft, "COOLDOWN", simulation);
        }
        if (simulation) return result(draft, "WOULD_APPLY", true);
        if (draft.action().equals("THUMBNAIL")) tokens.requireUploadScope();
        else tokens.requireForceSslScope();

        var now = clock.instant();
        if (!repository.claim(draft, selected, now)) return result(draft, "OTHER_CHANGE_PENDING", false);
        boolean attempted = false;
        try {
            LocalDate day = advisor.lastConservativeDay();
            var baseline = advisor.snapshot(draft.videoId(), new Window(day.minusDays(27), day));
            repository.baseline(draft, encode(baseline));
            current = changes.read(owner, draft.videoId());
            if (!Objects.equals(current.value(draft.action()), draft.previous())) {
                repository.finish(draft, "STALE", null, null, null, true);
                return result(draft, "STALE", false);
            }
            if (draft.action().equals("THUMBNAIL")) {
                var image = repository.image(id, choice);
                attempted = true;
                thumbnails.setThumbnail(draft.videoId(), image);
            } else {
                attempted = true;
                changes.update(draft.videoId(), current, draft.action(), selected);
            }
            repository.finish(draft, "APPLIED", selected, clock.instant(), null, true);
            return result(draft, "APPLIED", false);
        } catch (RuntimeException error) {
            boolean uncertain = attempted && YouTubeStudioClient.uncertain(error);
            repository.finish(draft, uncertain ? "UNKNOWN" : "READY", uncertain ? selected : null,
                    uncertain ? now : null, null, !uncertain);
            log.warn("video_optimization id={} action={} outcome={} exception={}", id, draft.action(),
                    uncertain ? "UNKNOWN" : "FAILED", error.getClass().getSimpleName());
            if (!uncertain) throw error;
            return result(draft, "UNKNOWN_REQUIRES_REVIEW", false);
        }
    }

    public List<HistoryRow> history(String videoId) {
        advisor.owned(videoId);
        return repository.history(advisor.channel(), videoId).stream().map(draft ->
                new HistoryRow(preview(draft), draft.appliedAt(), draft.applied(),
                        draft.beforeMetrics() == null ? null : decode(draft.beforeMetrics()),
                        repository.evaluations(draft.id()).stream().map(this::decode).toList())).toList();
    }

    public synchronized Evaluation evaluate(String id, int days, boolean refresh) {
        if (!List.of(7, 14, 28).contains(days)) throw new IllegalArgumentException("Finestre ammesse: 7, 14 o 28 giorni");
        var draft = repository.draft(advisor.channel(), id);
        if (!"APPLIED".equals(draft.state())) throw new IllegalArgumentException("Serve un intervento applicato");
        LocalDate change = draft.appliedAt().atZone(PerformanceAdvisor.PACIFIC).toLocalDate();
        var after = new Window(change.plusDays(1), change.plusDays(days));
        var before = new Window(change.minusDays(days), change.minusDays(1));
        if (after.to().isAfter(advisor.lastConservativeDay())) {
            return new Evaluation(id, days, "WAITING_FOR_DATA", before, after, null, null);
        }
        if (!refresh) {
            for (String payload : repository.evaluations(id)) {
                var node = decode(payload);
                if (node.path("days").asInt() == days) return new Evaluation(id, days, node.path("status").asText(),
                        before, after, node.path("previous"), node.path("subsequent"));
            }
        }
        var previous = advisor.snapshot(draft.videoId(), before);
        var subsequent = advisor.snapshot(draft.videoId(), after);
        var evaluation = new Evaluation(id, days, previous.sample().views() == null || subsequent.sample().views() == null
                ? "INSUFFICIENT_DATA" : "OBSERVATIONAL", before, after, previous, subsequent);
        repository.saveEvaluation(id, days, clock.instant(), encode(evaluation));
        return evaluation;
    }

    public List<Evaluation> dueEvaluations(boolean refresh) {
        return repository.applied(advisor.channel(), clock.instant().minus(Duration.ofDays(42))).stream().limit(10)
                .flatMap(draft -> List.of(7, 14, 28).stream().map(days -> evaluate(draft.id(), days, refresh))).toList();
    }

    public Set<String> cooling() {
        return repository.pendingOrCooling(advisor.channel(), clock.instant().minus(Duration.ofDays(14)));
    }

    private Preview preview(Draft draft) {
        var options = decode(draft.options()).elements();
        var content = new java.util.ArrayList<String>();
        options.forEachRemaining(node -> content.add(node.asText()));
        var confirmations = CHOICES.subList(0, content.size()).stream().map(choice -> confirmation(draft, choice)).toList();
        return new Preview(draft.id(), draft.videoId(), draft.action(), draft.revision(), draft.state(),
                draft.reason(), draft.previous(), content, confirmations);
    }

    private ApplyResult result(Draft draft, String status, boolean simulate) {
        meters.counter("chewbecca.performance.optimization", "outcome", status).increment();
        return new ApplyResult(draft.id(), draft.videoId(), draft.action(), status, simulate);
    }

    private static String confirmation(Draft draft, String choice) {
        return "CONFERMO " + draft.action() + " " + draft.id() + " REV " + draft.revision() + " " + choice;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Dati della proposta non serializzabili", error); }
    }

    private JsonNode decode(String text) {
        try { return json.readTree(text); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Dati della proposta non leggibili", error); }
    }

    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public record Preview(String id, String videoId, String action, int revision, String status, String reason,
                          String previous, List<String> options, List<String> confirmations) {}
    public record ApplyResult(String id, String videoId, String action, String status, boolean dryRun) {}
    public record Evaluation(String id, int days, String status, Window before, Window after,
                             Object previous, Object subsequent) {}
    public record HistoryRow(Preview draft, java.time.Instant changeAt, String newValue, JsonNode beforeMetrics,
                             List<JsonNode> evaluations) {}
}
