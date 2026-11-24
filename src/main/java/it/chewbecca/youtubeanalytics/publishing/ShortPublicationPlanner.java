package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import it.chewbecca.youtubeanalytics.service.YoutubeCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Dati: Shorts dei due mesi precedenti. Scelta: slot dei prossimi due giorni (48 ore).
 * Le API YouTube Analytics non forniscono la heatmap oraria dei viewer di YouTube Studio.
 * Qui confrontiamo il rendimento dei video per giorno/ora di pubblicazione, NON viewer online.
 */
@Service
public class ShortPublicationPlanner {
    public static final ZoneId PUBLICATION_ZONE = ZoneId.of("Europe/Rome");
    private static final ZoneId ANALYTICS_ZONE = ZoneId.of("America/Los_Angeles");
    private static final int MAX_HISTORICAL_VIDEOS = 100;
    private final YoutubeCatalogService catalog;
    private final ShortsClassifier classifier;
    private final YouTubeAnalyticsClient analytics;
    private final Clock clock;

    public ShortPublicationPlanner(YoutubeCatalogService catalog, ShortsClassifier classifier,
                                   YouTubeAnalyticsClient analytics, Clock clock) {
        this.catalog = catalog;
        this.classifier = classifier;
        this.analytics = analytics;
        this.clock = clock;
    }

    @Cacheable("shortPublishingRecommendation")
    public Recommendation recommend() {
        Instant now = clock.instant();
        Instant historyStart = now.atZone(PUBLICATION_ZONE).minusMonths(2).toInstant();
        LocalDate latestCompleteAnalyticsDay = now.atZone(ANALYTICS_ZONE).toLocalDate().minusDays(2);
        List<YouTubeDataClient.VideoMetadata> eligible = catalog.videos().stream()
                .filter(YouTubeDataClient.VideoMetadata::available)
                .filter(v -> "public".equals(v.privacyStatus()))
                .filter(v -> v.publishedAt() != null && !v.publishedAt().isBefore(historyStart))
                .filter(v -> v.publishedAt().isBefore(now))
                .filter(v -> classifier.classify(v).type() == ContentType.SHORT)
                // Solo Shorts con sette giornate complete di dati per un confronto omogeneo.
                .filter(v -> !v.publishedAt().atZone(ANALYTICS_ZONE).toLocalDate().plusDays(6)
                        .isAfter(latestCompleteAnalyticsDay))
                .sorted(Comparator.comparing(YouTubeDataClient.VideoMetadata::publishedAt).reversed())
                .toList();
        boolean truncated = eligible.size() > MAX_HISTORICAL_VIDEOS;
        List<HistoryPoint> samples = new ArrayList<>();
        for (YouTubeDataClient.VideoMetadata v : eligible.stream().limit(MAX_HISTORICAL_VIDEOS).toList()) {
            LocalDate analyticsPublished = v.publishedAt().atZone(ANALYTICS_ZONE).toLocalDate();
            long views = analytics.getVideoFirstWeekViews(v.videoId(), analyticsPublished);
            if (views < 0) continue;
            ZonedDateTime local = v.publishedAt().atZone(PUBLICATION_ZONE);
            samples.add(new HistoryPoint(local.getDayOfWeek(), local.getHour() + local.getMinute() / 60.0,
                    Math.log1p(views), views));
        }
        ZonedDateTime minStart = now.plus(2, ChronoUnit.HOURS).atZone(PUBLICATION_ZONE);
        ZonedDateTime end = now.plus(48, ChronoUnit.HOURS).atZone(PUBLICATION_ZONE);
        ZonedDateTime candidate = minStart.truncatedTo(ChronoUnit.HOURS);
        candidate = candidate.withMinute(candidate.getMinute() <= 30 ? 30 : 0);
        if (candidate.isBefore(minStart)) candidate = candidate.plusMinutes(30);
        List<Slot> slots = new ArrayList<>();
        while (!candidate.isAfter(end)) {
            if (candidate.getHour() >= 10 && candidate.getHour() <= 21) {
                double score = score(candidate, samples);
                slots.add(new Slot(candidate.toOffsetDateTime(), score));
            }
            candidate = candidate.plusMinutes(30);
        }
        if (slots.isEmpty()) throw new IllegalStateException("Nessuno slot disponibile nelle prossime 48 ore");
        // A parità di score preferisce le 18:30, poi il primo slot cronologico.
        Comparator<Slot> ranking = Comparator.comparingDouble(Slot::score).reversed()
                .thenComparingDouble(s -> Math.abs(s.dateTime().getHour() + s.dateTime().getMinute() / 60.0 - 18.5))
                .thenComparing(Slot::dateTime);
        List<Slot> ranked = slots.stream().sorted(ranking).toList();
        List<Slot> alternatives = new ArrayList<>();
        for (Slot slot : ranked) {
            if (alternatives.stream().noneMatch(other -> Math.abs(Duration.between(
                    other.dateTime(), slot.dateTime()).toMinutes()) < 120)) {
                alternatives.add(slot);
            }
            if (alternatives.size() == 3) break;
        }
        String confidence = samples.size() >= 20 ? "MEDIA" : "BASSA";
        String explanation = samples.isEmpty()
                ? "Nessuno Short maturo utilizzabile negli ultimi due mesi: proposta prudenziale alle 18:30, non basata su prove statistiche."
                : "Stima basata su " + samples.size() + " Shorts negli ultimi due mesi, " +
                "confrontando le visualizzazioni dei primi 7 giorni, giorno e ora di pubblicazione. " +
                "La correlazione non dimostra che l'orario causi le visualizzazioni.";
        return new Recommendation(historyStart, now, now.plus(48, ChronoUnit.HOURS),
                samples.size(), truncated, confidence, explanation,
                alternatives.getFirst(), List.copyOf(alternatives));
    }

    static double score(ZonedDateTime candidate, List<HistoryPoint> points) {
        if (points.isEmpty()) return 0;
        double global = points.stream().mapToDouble(HistoryPoint::logViews).average().orElse(0);
        double total = 0;
        double weights = 0;
        for (HistoryPoint p : points) {
            int dayDistance = Math.abs(candidate.getDayOfWeek().getValue() - p.day().getValue());
            dayDistance = Math.min(dayDistance, 7 - dayDistance);
            double dayWeight = dayDistance == 0 ? 2.0 : dayDistance == 1 ? 0.75 : 0.30;
            double distance = Math.abs(candidate.getHour() + candidate.getMinute()/60.0 - p.hour());
            double hourWeight = Math.exp(-distance / 3.0);
            double w = dayWeight * hourWeight;
            total += w * p.logViews();
            weights += w;
        }
        // Shrinkage: evita di attribuire troppa importanza a pochi upload in una fascia.
        return global + (total / weights - global) * (weights / (weights + 5.0));
    }

    record HistoryPoint(DayOfWeek day, double hour, double logViews, long views) {}
    public record Slot(OffsetDateTime dateTime, double score) {}
    public record Recommendation(Instant historicalFrom, Instant historicalTo, Instant proposalWindowEnds,
                                 int videosAnalyzed, boolean historyTruncated, String confidence,
                                 String methodology, Slot recommended, List<Slot> alternatives) {}
}
