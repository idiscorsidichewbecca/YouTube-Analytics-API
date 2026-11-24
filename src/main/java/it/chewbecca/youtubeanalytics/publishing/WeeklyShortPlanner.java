package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import it.chewbecca.youtubeanalytics.service.YoutubeCatalogService;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Piano non in cache: storico quattro mesi e calendario YouTube aggiornato a ogni richiesta. */
@Service
public class WeeklyShortPlanner {
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final ZoneId ANALYTICS = ZoneId.of("America/Los_Angeles");
    private static final Duration BUFFER = Duration.ofHours(3);
    private static final int MAX_SAMPLES = 150;

    private final YoutubeCatalogService catalog;
    private final ShortsClassifier classifier;
    private final YouTubeAnalyticsClient analytics;
    private final YouTubePublicationCalendar calendar;
    private final ShortDraftRepository drafts;
    private final Clock clock;

    public WeeklyShortPlanner(YoutubeCatalogService catalog, ShortsClassifier classifier,
                              YouTubeAnalyticsClient analytics, YouTubePublicationCalendar calendar,
                              ShortDraftRepository drafts, Clock clock) {
        this.catalog = catalog;
        this.classifier = classifier;
        this.analytics = analytics;
        this.calendar = calendar;
        this.drafts = drafts;
        this.clock = clock;
    }

    public WeekPlan plan() {
        Instant now = clock.instant();
        Instant from = now.plus(2, ChronoUnit.HOURS);
        Instant until = weekEnds(now);
        if (from.isAfter(until)) {
            throw new IllegalStateException("Nessuna fascia oraria utile rimasta nella settimana corrente");
        }

        Instant historyStart = now.atZone(ROME).minusMonths(4).toInstant();
        LocalDate lastMatureDay = now.atZone(ANALYTICS).toLocalDate().minusDays(2);
        List<YouTubeDataClient.VideoMetadata> eligible = catalog.videos().stream()
                .filter(YouTubeDataClient.VideoMetadata::available)
                .filter(v -> "public".equals(v.privacyStatus()))
                .filter(v -> v.publishedAt() != null && !v.publishedAt().isBefore(historyStart)
                        && v.publishedAt().isBefore(now))
                .filter(v -> classifier.classify(v).type() == ContentType.SHORT)
                .filter(v -> !v.publishedAt().atZone(ANALYTICS).toLocalDate().plusDays(6)
                        .isAfter(lastMatureDay))
                .sorted(Comparator.comparing(YouTubeDataClient.VideoMetadata::publishedAt).reversed())
                .toList();
        List<HistoricalShort> history = new ArrayList<>();
        for (var video : eligible.stream().limit(MAX_SAMPLES).toList()) {
            var metrics = analytics.getShortFirstWeekPerformance(video.videoId(),
                    video.publishedAt().atZone(ANALYTICS).toLocalDate());
            if (metrics.views() > 0) {
                history.add(new HistoricalShort(video.videoId(), video.title(), video.publishedAt(), metrics));
            }
        }
        List<ShortPublicationPlanner.HistoryPoint> samples = history.stream()
                .map(shortVideo -> {
                    var local = shortVideo.publishedAt().atZone(ROME);
                    return new ShortPublicationPlanner.HistoryPoint(local.getDayOfWeek(),
                            local.getHour() + local.getMinute() / 60.0,
                            performanceScore(shortVideo, history), shortVideo.metrics().views());
                })
                .toList();

        List<BusySlot> busy = occupied(from.minus(BUFFER), until.plus(BUFFER), null);
        List<ShortPublicationPlanner.Slot> candidates = new ArrayList<>();
        ZonedDateTime t = from.atZone(ROME).truncatedTo(ChronoUnit.HOURS);
        while (t.toInstant().isBefore(from)) t = t.plusMinutes(30);
        while (!t.toInstant().isAfter(until)) {
            if (clear(t.toInstant(), busy)) {
                candidates.add(new ShortPublicationPlanner.Slot(t.toOffsetDateTime(),
                        ShortPublicationPlanner.score(t, samples)));
            }
            t = t.plusMinutes(30);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Nessuno slot libero nella settimana corrente: " +
                    "non forzare sovrapposizioni, modifica settimana o distanza minima");
        }
        candidates.sort(Comparator.comparingDouble(ShortPublicationPlanner.Slot::score).reversed()
                .thenComparingDouble(s -> Math.abs(s.dateTime().getHour() +
                        s.dateTime().getMinute()/60.0 - 18.5))
                .thenComparing(ShortPublicationPlanner.Slot::dateTime));
        List<ShortPublicationPlanner.Slot> alternatives = new ArrayList<>();
        for (var candidate : candidates) {
            if (alternatives.stream().noneMatch(x -> Math.abs(Duration.between(
                    x.dateTime().toInstant(), candidate.dateTime().toInstant()).toMinutes()) < 120)) {
                alternatives.add(candidate);
            }
            if (alternatives.size() == 3) break;
        }
        String methodology = samples.isEmpty()
                ? "Storico utilizzabile assente. A parità di punteggio hanno precedenza le fasce vicine alle 18:30."
                : "Primi sette giorni. Il punteggio combina copertura (60%), percentuale media guardata (25%) "
                        + "e interazioni per visualizzazione (15%). Le fasce con pochi esempi pesano meno.";
        List<HistoricalShort> examples = history.stream()
                .sorted(Comparator.comparingDouble((HistoricalShort v) -> performanceScore(v, history)).reversed())
                .limit(3)
                .toList();
        return new WeekPlan(historyStart, now, until, samples.size(), eligible.size() > MAX_SAMPLES,
                samples.size() >= 20 ? "MEDIA" : "BASSA", BUFFER.toHours(), methodology,
                alternatives.getFirst(), List.copyOf(alternatives),
                busy.stream().map(BusySlot::when).distinct().sorted().toList(), examples);
    }

    private static double performanceScore(HistoricalShort video, List<HistoricalShort> history) {
        return 0.60 * (0.70 * percentile(video, history, s -> s.metrics().views())
                        + 0.30 * percentile(video, history, HistoricalShort::engagedReach))
                + 0.25 * percentile(video, history, s -> s.metrics().averageViewPercentage())
                + 0.15 * percentile(video, history, HistoricalShort::interactionRate);
    }

    private static double percentile(HistoricalShort video, List<HistoricalShort> history,
                                     java.util.function.ToDoubleFunction<HistoricalShort> metric) {
        double value = metric.applyAsDouble(video);
        double rank = 0;
        for (HistoricalShort sample : history) {
            int comparison = Double.compare(value, metric.applyAsDouble(sample));
            if (comparison > 0) rank++;
            else if (comparison == 0) rank += 0.5;
        }
        return rank / history.size();
    }

    static void validateCurrentWeek(Instant when, Instant now) {
        if (when.isAfter(weekEnds(now))) {
            throw new IllegalArgumentException("Scegli un orario entro la domenica della settimana corrente (ora italiana)");
        }
    }

    private static Instant weekEnds(Instant now) {
        return now.atZone(ROME).toLocalDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .atTime(23, 59, 59).atZone(ROME).toInstant();
    }

    /** Da richiamare appena prima dell'upload: verificare nuovamente il calendario, mai fidarsi di una bozza vecchia. */
    public void assertAvailable(Instant proposed, String excludeDraftId) {
        List<BusySlot> busy = occupied(proposed.minus(BUFFER), proposed.plus(BUFFER), excludeDraftId);
        if (!clear(proposed, busy)) {
            throw new IllegalStateException("Orario già occupato da un'altra pubblicazione: " +
                    "crea una nuova bozza con chewbecca_plan_weekly_short, nessun upload avviato");
        }
    }

    private List<BusySlot> occupied(Instant from, Instant to, String excludeDraftId) {
        List<BusySlot> busy = new ArrayList<>();
        for (var p : calendar.between(from, to)) {
            busy.add(new BusySlot(p.publishAt(), "youtube:" + p.videoId()));
        }
        for (var draft : drafts.findByPublishAtBetweenAndStatusNot(from, to, ShortDraft.Status.DRAFT)) {
            if (!draft.getId().equals(excludeDraftId)) {
                busy.add(new BusySlot(draft.getPublishAt(), "draft:" + draft.getId()));
            }
        }
        return List.copyOf(busy);
    }

    static boolean clear(Instant proposed, List<BusySlot> busy) {
        return busy.stream().noneMatch(b -> Duration.between(proposed, b.when()).abs().compareTo(BUFFER) < 0);
    }

    public record BusySlot(Instant when, String source) {}
    public record HistoricalShort(String videoId, String title, Instant publishedAt,
                                  YouTubeAnalyticsClient.AnalyticsMetrics metrics) {
        long engagedReach() {
            return metrics.engagedViews() > 0 ? metrics.engagedViews() : metrics.views();
        }

        double interactionRate() {
            double interactions = (double) metrics.likes() + metrics.comments() + metrics.shares()
                    + metrics.subscribersGained();
            // Many interactions on only a few views should not outperform mature Shorts.
            return interactions / (metrics.views() + 200.0);
        }
    }

    public record WeekPlan(Instant historicalFrom, Instant historicalTo, Instant weekEnds,
                           int videosAnalyzed, boolean historyTruncated, String confidence,
                           long minGapHours, String methodology,
                           ShortPublicationPlanner.Slot recommended,
                           List<ShortPublicationPlanner.Slot> alternatives, List<Instant> occupiedPublicationTimes,
                           List<HistoricalShort> historicalExamples) {}
}
