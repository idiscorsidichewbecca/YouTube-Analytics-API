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
        when(drafts.findByPublishAtBetweenAndStatusNot(any(), any(), eq(ShortDraft.Status.DRAFT))).thenReturn(List.of());

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

    @Test void avoidsAlreadyScheduledNormalVideosAndShorts() {
        Instant existingVideo = Instant.parse("2026-11-24T18:00:00Z");
        Instant existingShort = Instant.parse("2026-11-25T19:00:00Z");
        when(catalog.videos()).thenReturn(List.of());
        when(drafts.findByPublishAtBetweenAndStatusNot(any(), any(), eq(ShortDraft.Status.DRAFT))).thenReturn(List.of());
        when(calendar.between(any(), any())).thenReturn(List.of(
                new YouTubePublicationCalendar.Publication("normalVideo", "Video lungo", existingVideo, true),
                new YouTubePublicationCalendar.Publication("someShort", "Short", existingShort, true)));
        var plan = planner.plan();
        for (var slot : plan.alternatives()) {
            assertThat(Duration.between(existingVideo, slot.dateTime().toInstant()).abs().toMinutes())
                    .isGreaterThanOrEqualTo(180L);
            assertThat(Duration.between(existingShort, slot.dateTime().toInstant()).abs().toMinutes())
                    .isGreaterThanOrEqualTo(180L);
        }
    }

    @Test void rejectsConflictingSlotBeforeUploading() {
        Instant proposed = Instant.parse("2026-11-26T17:00:00Z");
        when(calendar.between(any(), any())).thenReturn(List.of(
                new YouTubePublicationCalendar.Publication("already", "Programmed", proposed.plusSeconds(60), true)));
        when(drafts.findByPublishAtBetweenAndStatusNot(any(), any(), eq(ShortDraft.Status.DRAFT))).thenReturn(List.of());
        assertThatThrownBy(() -> planner.assertAvailable(proposed, "current-draft"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Orario già occupato");
    }

    @Test void failsClosedIfCalendarCannotBeRead() {
        when(catalog.videos()).thenReturn(List.of());
        when(calendar.between(any(), any())).thenThrow(new IllegalStateException("Google non raggiungibile"));
        assertThatThrownBy(planner::plan).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Google non raggiungibile");
    }

    @Test void exactlyThreeHoursAwayIsAllowed() {
        Instant proposed = Instant.parse("2026-11-24T18:00:00Z");
        assertThat(WeeklyShortPlanner.clear(proposed, List.of(
                new WeeklyShortPlanner.BusySlot(proposed.minusSeconds(10800), "normal")))).isTrue();
        assertThat(WeeklyShortPlanner.clear(proposed, List.of(
                new WeeklyShortPlanner.BusySlot(proposed.minusSeconds(10799), "short")))).isFalse();
    }

    private static YouTubeDataClient.VideoMetadata shortVideo(String id, Instant publishedAt) {
        return new YouTubeDataClient.VideoMetadata(id, "Test", "Test", publishedAt,
                Duration.ofSeconds(45), null, List.of("#shorts"), "24", "none", "public",
                false, false, 5000, 100, 2, true);
    }
}
