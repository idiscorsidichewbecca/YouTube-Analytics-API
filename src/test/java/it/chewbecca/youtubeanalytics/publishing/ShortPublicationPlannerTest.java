package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import it.chewbecca.youtubeanalytics.service.YoutubeCatalogService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShortPublicationPlannerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC);
    private final YoutubeCatalogService catalog = mock(YoutubeCatalogService.class);
    private final ShortsClassifier classifier = mock(ShortsClassifier.class);
    private final YouTubeAnalyticsClient analytics = mock(YouTubeAnalyticsClient.class);
    private final ShortPublicationPlanner planner = new ShortPublicationPlanner(catalog, classifier, analytics, clock);

    @Test
    void usesTwoMonthsOfHistoryToRecommendOnlyWithinNext48Hours() {
        YouTubeDataClient.VideoMetadata valid = video("recent", Instant.parse("2026-10-20T18:00:00Z"));
        YouTubeDataClient.VideoMetadata tooOld = video("old", Instant.parse("2026-08-01T18:00:00Z"));
        when(catalog.videos()).thenReturn(List.of(valid, tooOld));
        when(classifier.classify(any())).thenReturn(new ShortsClassifier.Classification(ContentType.SHORT, "TEST", "HIGH"));
        when(analytics.getVideoFirstWeekViews(any(), any())).thenReturn(4000L);

        var result = planner.recommend();
        assertThat(result.videosAnalyzed()).isEqualTo(1);
        assertThat(result.historicalFrom()).isEqualTo(Instant.parse("2026-09-24T07:00:00Z"));
        assertThat(result.proposalWindowEnds()).isEqualTo(clock.instant().plus(Duration.ofHours(48)));
        assertThat(result.recommended().dateTime().toInstant()).isAfter(clock.instant());
        assertThat(result.recommended().dateTime().toInstant()).isBeforeOrEqualTo(result.proposalWindowEnds());
        verify(analytics).getVideoFirstWeekViews(eq("recent"), any());
        verify(analytics, never()).getVideoFirstWeekViews(eq("old"), any());
    }

    @Test
    void offersExplicitLowConfidenceFallbackWhenNoHistoricalShorts() {
        when(catalog.videos()).thenReturn(List.of());
        var result = planner.recommend();
        assertThat(result.confidence()).isEqualTo("BASSA");
        assertThat(result.videosAnalyzed()).isZero();
        assertThat(result.methodology()).contains("Nessuno Short maturo");
        assertThat(result.recommended().dateTime().getHour()).isEqualTo(18);
        assertThat(result.recommended().dateTime().getMinute()).isEqualTo(30);
    }

    private YouTubeDataClient.VideoMetadata video(String id, Instant published) {
        return new YouTubeDataClient.VideoMetadata(id, "Titolo", "Test", published,
                Duration.ofSeconds(45), null, List.of("#shorts"), "24", "none", "public",
                false, false, 5000, 100, 2, true);
    }
}
