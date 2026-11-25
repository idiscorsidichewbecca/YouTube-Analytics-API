package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import it.chewbecca.youtubeanalytics.service.YoutubeCatalogService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyShortPlannerTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC);
    final YoutubeCatalogService catalog = mock(YoutubeCatalogService.class);
    final ShortsClassifier classifier = mock(ShortsClassifier.class);
    final YouTubeAnalyticsClient analytics = mock(YouTubeAnalyticsClient.class);
    final YouTubePublicationCalendar calendar = mock(YouTubePublicationCalendar.class);
    final ShortDraftRepository drafts = mock(ShortDraftRepository.class);
    final WeeklyShortPlanner planner = new WeeklyShortPlanner(catalog, classifier, analytics, calendar, drafts, clock);

    @Test void considersFourMonthsAndKeepsAllSlotsInsideCurrentWeek() {
        var withinFourMonths = shortVideo("recent", Instant.parse("2026-08-15T15:00:00Z"));
        var outsideFourMonths = shortVideo("old", Instant.parse("2026-05-20T15:00:00Z"));
        when(catalog.videos()).thenReturn(List.of(withinFourMonths, outsideFourMonths));
        when(classifier.classify(any())).thenReturn(new ShortsClassifier.Classification(ContentType.SHORT, "TEST", "HIGH"));
        when(analytics.getShortFirstWeekPerformance(eq("recent"), any()))
                .thenReturn(new YouTubeAnalyticsClient.AnalyticsMetrics(5000, 9000, 8, 100, 24,
                        0, 0, 76, 10, 0));
        when(calendar.between(any(), any())).thenReturn(List.of());
        when(drafts.findReservations(any(), any(), eq(ShortDraft.Status.DRAFT))).thenReturn(List.of());

        var plan = planner.plan();
        assertThat(plan.videosAnalyzed()).isEqualTo(1);
        assertThat(plan.historicalExamples()).singleElement()
                .satisfies(example -> assertThat(example.metrics().averageViewPercentage()).isEqualTo(76));
        assertThat(plan.weekEnds().atZone(WeeklyShortPlanner.ROME).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(plan.recommended().dateTime().toInstant()).isBeforeOrEqualTo(plan.weekEnds());
        assertThat(plan.recommended().dateTime().toInstant()).isAfter(clock.instant());
        verify(analytics).getShortFirstWeekPerformance(eq("recent"), any());
        verify(analytics, never()).getShortFirstWeekPerformance(eq("old"), any());
    }

    @Test void excludesWholeItalianDaysEvenIfTheVideoWasPublishedBeforePlanning() {
        Instant publishedToday = Instant.parse("2026-11-24T00:05:00Z");
        Instant scheduledTomorrow = Instant.parse("2026-11-25T19:00:00Z");
        when(catalog.videos()).thenReturn(List.of());
        when(calendar.between(any(), any())).thenReturn(List.of(
                new YouTubePublicationCalendar.Publication("normalVideo", "Video lungo", publishedToday, false, true),
                new YouTubePublicationCalendar.Publication("someShort", "Short", scheduledTomorrow, true, true)));

        var plan = planner.plan();

        assertThat(plan.occupiedDays()).containsExactly(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25));
        assertThat(plan.alternatives()).allSatisfy(slot ->
                assertThat(slot.dateTime().toLocalDate()).isAfter(LocalDate.of(2026, 11, 25)));
        verify(calendar).between(Instant.parse("2026-11-23T23:00:00Z"), Instant.parse("2026-11-29T23:00:00Z"));
    }

    @Test void rejectsAnUploadIfTheWholeDayIsNoLongerFree() {
        Instant nightBeforeInUtc = Instant.parse("2026-11-25T23:05:00Z");
        Instant proposed = Instant.parse("2026-11-26T20:00:00Z");
        when(calendar.between(Instant.parse("2026-11-25T23:00:00Z"), Instant.parse("2026-11-26T23:00:00Z")))
                .thenReturn(List.of(new YouTubePublicationCalendar.Publication(
                        "already", "Programmed", nightBeforeInUtc, false, true)));

        assertThatThrownBy(() -> planner.assertAvailable(proposed, "current-draft"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Giornata già occupata");
    }

    @Test void failsClosedIfCalendarCannotBeRead() {
        when(calendar.between(any(), any())).thenThrow(new IllegalStateException("Google non raggiungibile"));
        assertThatThrownBy(planner::plan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Google non raggiungibile");
        verifyNoInteractions(catalog, analytics);
    }

    @Test void reportsNoFreeDayWithoutSearchingPastSunday() {
        var sundayClock = Clock.fixed(Instant.parse("2026-11-29T08:00:00Z"), ZoneOffset.UTC);
        var sundayPlanner = new WeeklyShortPlanner(catalog, classifier, analytics, calendar, drafts, sundayClock);
        when(calendar.between(any(), any())).thenReturn(List.of(new YouTubePublicationCalendar.Publication(
                "existing", "Programmed", Instant.parse("2026-11-29T18:00:00Z"), true, true)));

        assertThatThrownBy(sundayPlanner::plan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nessuna giornata libera");
        verifyNoInteractions(catalog, analytics);
    }

    @Test void explainsRecommendedHourWithObservedCountsAndMedians() {
        var better = shortVideo("better", Instant.parse("2026-11-13T17:30:00Z"));
        var weaker = shortVideo("weaker", Instant.parse("2026-11-10T17:30:00Z"));
        when(catalog.videos()).thenReturn(List.of(better, weaker));
        when(classifier.classify(any())).thenReturn(new ShortsClassifier.Classification(ContentType.SHORT, "TEST", "HIGH"));
        when(analytics.getShortFirstWeekPerformance(eq("better"), any()))
                .thenReturn(new YouTubeAnalyticsClient.AnalyticsMetrics(8000, 10500, 30, 250, 20,
                        0, 0, 82, 5, 0));
        when(analytics.getShortFirstWeekPerformance(eq("weaker"), any()))
                .thenReturn(new YouTubeAnalyticsClient.AnalyticsMetrics(1000, 11500, 2, 3, 0,
                        0, 0, 45, 0, 0));

        var plan = planner.plan();

        assertThat(plan.recommended().dateTime().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(plan.confidence()).isEqualTo("BASSA");
        var recommendationEvidence = plan.slotEvidence().getFirst();
        assertThat(recommendationEvidence.sampleCount()).isEqualTo(1);
        assertThat(recommendationEvidence.medianEngagedViews()).isEqualTo(8000.0);
        assertThat(recommendationEvidence.medianAveragePercentageViewed()).isEqualTo(82.0);
    }

    private static YouTubeDataClient.VideoMetadata shortVideo(String id, Instant publishedAt) {
        return new YouTubeDataClient.VideoMetadata(id, "Test", "Test", publishedAt,
                Duration.ofSeconds(45), null, List.of("#shorts"), "24", "none", "public",
                false, false, 5000, 100, 2, true);
    }
}
