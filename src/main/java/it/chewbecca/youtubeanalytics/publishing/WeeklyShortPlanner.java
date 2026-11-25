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
import java.util.function.ToDoubleFunction;

/** Piano non in cache: storico quattro mesi e calendario YouTube aggiornato a ogni richiesta. */
@Service
public class WeeklyShortPlanner {
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final ZoneId ANALYTICS = ZoneId.of("America/Los_Angeles");
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
        Instant until = weekEndExclusive(now);
        if (!from.isBefore(until)) {
            throw new IllegalStateException("Nessuna fascia oraria utile rimasta nella settimana corrente");
        }

        LocalDate firstDay = from.atZone(ROME).toLocalDate();
        List<Instant> busy = occupied(firstDay.atStartOfDay(ROME).toInstant(), until, null);
        Set<LocalDate> busyDays = new TreeSet<>();
        for (Instant publication : busy) {
            busyDays.add(publication.atZone(ROME).toLocalDate());
        }
        List<ZonedDateTime> availableTimes = new ArrayList<>();
        ZonedDateTime candidateTime = from.atZone(ROME).truncatedTo(ChronoUnit.HOURS);
        while (candidateTime.toInstant().isBefore(from)) candidateTime = candidateTime.plusMinutes(30);
        while (candidateTime.toInstant().isBefore(until)) {
            if (!busyDays.contains(candidateTime.toLocalDate())) {
                availableTimes.add(candidateTime);
            }
            candidateTime = candidateTime.plusMinutes(30);
        }
        if (availableTimes.isEmpty()) {
            throw new IllegalStateException("Nessuna giornata libera con orari disponibili entro domenica");
        }

        PublicationHistory publicationHistory = publicationHistory(now);
        List<HistoricalShort> history = publicationHistory.videos();
        boolean hasEngagedViews = publicationHistory.hasEngagedViews();
        List<ShortPublicationPlanner.Slot> candidates = rankSlots(availableTimes, publicationHistory);
        List<ShortPublicationPlanner.Slot> alternatives = new ArrayList<>();
        for (var candidate : candidates) {
            if (alternatives.stream().noneMatch(x -> Math.abs(Duration.between(
                    x.dateTime().toInstant(), candidate.dateTime().toInstant()).toMinutes()) < 120)) {
                alternatives.add(candidate);
            }
            if (alternatives.size() == 3) break;
        }
        String methodology = history.isEmpty()
                ? "Storico utilizzabile assente. A parità di punteggio hanno precedenza le fasce vicine alle 18:30."
                : "Primi sette giorni. Punteggio con visualizzazioni soprattutto coinvolte (60%), percentuale "
                        + "media guardata (25%) e interazioni per visualizzazione (15%). Le fasce con pochi esempi "
                        + "pesano meno. Il dettaglio degli slot conta gli Shorts dello stesso giorno della settimana "
                        + "pubblicati entro due ore dall'orario proposto.";
        List<HistoricalShort> examples = history.stream()
                .sorted(Comparator.comparingDouble((HistoricalShort v) -> performanceScore(v, history, hasEngagedViews)).reversed())
                .limit(3)
                .toList();
        List<SlotEvidence> evidence = alternatives.stream()
                .map(slot -> evidenceFor(slot.dateTime(), history, hasEngagedViews))
                .toList();
        return new WeekPlan(publicationHistory.from(), now, until.minusNanos(1), history.size(), publicationHistory.truncated(),
                evidence.getFirst().sampleCount() >= 5 ? "MEDIA" : "BASSA", methodology,
                alternatives.getFirst(), List.copyOf(alternatives), List.copyOf(busyDays), busy, examples, evidence);
    }

    PublicationHistory publicationHistory(Instant now) {
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
        return new PublicationHistory(historyStart, history, eligible.size() > MAX_SAMPLES);
    }

    List<ShortPublicationPlanner.Slot> rankSlots(List<ZonedDateTime> availableTimes, PublicationHistory publicationHistory) {
        List<HistoricalShort> history = publicationHistory.videos();
        boolean hasEngagedViews = publicationHistory.hasEngagedViews();
        List<ShortPublicationPlanner.HistoryPoint> samples = history.stream()
                .map(shortVideo -> {
                    var local = shortVideo.publishedAt().atZone(ROME);
                    return new ShortPublicationPlanner.HistoryPoint(local.getDayOfWeek(),
                            local.getHour() + local.getMinute() / 60.0,
                            performanceScore(shortVideo, history, hasEngagedViews), shortVideo.metrics().views());
                })
                .toList();

        List<ShortPublicationPlanner.Slot> candidates = new ArrayList<>(availableTimes.stream()
                .map(time -> new ShortPublicationPlanner.Slot(time.toOffsetDateTime(),
                        ShortPublicationPlanner.score(time, samples)))
                .toList());
        candidates.sort(Comparator.comparingDouble(ShortPublicationPlanner.Slot::score).reversed()
                .thenComparingDouble(s -> Math.abs(s.dateTime().getHour() +
                        s.dateTime().getMinute()/60.0 - 18.5))
                .thenComparing(ShortPublicationPlanner.Slot::dateTime));
        return candidates;
    }

    record PublicationHistory(Instant from, List<HistoricalShort> videos, boolean truncated) {
        boolean hasEngagedViews() {
            return videos.stream().anyMatch(video -> video.metrics().engagedViews() > 0);
        }
    }

    static SlotEvidence evidenceFor(OffsetDateTime candidate, List<HistoricalShort> history,
                                            boolean hasEngagedViews) {
        List<HistoricalShort> comparable = history.stream().filter(video -> {
            ZonedDateTime time = video.publishedAt().atZone(ROME);
            return time.getDayOfWeek() == candidate.getDayOfWeek()
                    && Duration.between(candidate.toLocalTime(), time.toLocalTime()).abs()
                            .compareTo(Duration.ofHours(2)) <= 0;
        }).toList();
        return new SlotEvidence(candidate, comparable.size(),
                hasEngagedViews ? median(comparable, video -> video.metrics().engagedViews()) : null,
                median(comparable, video -> video.metrics().views()),
                median(comparable, video -> video.metrics().averageViewPercentage()));
    }

    private static Double median(List<HistoricalShort> history, ToDoubleFunction<HistoricalShort> metric) {
        double[] sorted = history.stream().mapToDouble(metric).sorted().toArray();
        if (sorted.length == 0) return null;
        int upperMiddle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[upperMiddle - 1] + sorted[upperMiddle]) / 2
                : sorted[upperMiddle];
    }

    private static double performanceScore(HistoricalShort video, List<HistoricalShort> history,
                                           boolean hasEngagedViews) {
        double views = percentile(video, history, s -> s.metrics().views());
        double reach = hasEngagedViews
                ? 0.75 * percentile(video, history, s -> s.metrics().engagedViews()) + 0.25 * views
                : views;
        return 0.60 * reach
                + 0.25 * percentile(video, history, s -> s.metrics().averageViewPercentage())
                + 0.15 * percentile(video, history, HistoricalShort::interactionRate);
    }

    private static double percentile(HistoricalShort video, List<HistoricalShort> history,
                                     ToDoubleFunction<HistoricalShort> metric) {
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
        if (!when.isBefore(weekEndExclusive(now))) {
            throw new IllegalArgumentException("Scegli un orario entro la domenica della settimana corrente (ora italiana)");
        }
    }

    private static Instant weekEndExclusive(Instant now) {
        return now.atZone(ROME).toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(ROME).toInstant();
    }

    /** Rilegge l'intera giornata italiana subito prima dell'upload. */
    public void assertAvailable(Instant proposed, String excludeDraftId) {
        LocalDate day = proposed.atZone(ROME).toLocalDate();
        Instant start = day.atStartOfDay(ROME).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ROME).toInstant();
        if (!occupied(start, end, excludeDraftId).isEmpty()) {
            throw new IllegalStateException("Giornata già occupata: scegli una nuova data con chewbecca_plan_weekly_short");
        }
    }

    private List<Instant> occupied(Instant from, Instant until, String excludeDraftId) {
        List<Instant> busy = new ArrayList<>();
        for (var publication : calendar.between(from, until)) {
            busy.add(publication.publishAt());
        }
        for (var draft : drafts.findReservations(from, until, ShortDraft.Status.DRAFT)) {
            if (!draft.getId().equals(excludeDraftId)) {
                busy.add(draft.getPublishAt());
            }
        }
        return busy.stream().distinct().sorted().toList();
    }

    public record HistoricalShort(String videoId, String title, Instant publishedAt,
                                  YouTubeAnalyticsClient.AnalyticsMetrics metrics) {
        double interactionRate() {
            double interactions = (double) metrics.likes() + metrics.comments() + metrics.shares()
                    + metrics.subscribersGained();
            // Many interactions on only a few views should not outperform mature Shorts.
            return interactions / (metrics.views() + 200.0);
        }
    }

    public record SlotEvidence(OffsetDateTime dateTime, int sampleCount, Double medianEngagedViews,
                               Double medianViews, Double medianAveragePercentageViewed) {}

    public record WeekPlan(Instant historicalFrom, Instant historicalTo, Instant weekEnds,
                           int videosAnalyzed, boolean historyTruncated, String confidence,
                           String methodology, ShortPublicationPlanner.Slot recommended,
                           List<ShortPublicationPlanner.Slot> alternatives, List<LocalDate> occupiedDays,
                           List<Instant> occupiedPublicationTimes,
                           List<HistoricalShort> historicalExamples, List<SlotEvidence> slotEvidence) {}
}
