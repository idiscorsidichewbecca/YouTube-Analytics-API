package it.chewbecca.youtubeanalytics.publishing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchShortPlannerTest {
    private final Instant now = Instant.parse("2026-11-24T08:00:00Z");
    private final YouTubePublicationCalendar calendar = mock(YouTubePublicationCalendar.class);
    private final ShortDraftRepository drafts = mock(ShortDraftRepository.class);
    private final WeeklyShortPlanner history = mock(WeeklyShortPlanner.class);
    private final BatchShortPlanner planner = new BatchShortPlanner(
            history, calendar, drafts, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void plansTenAcrossFourWeeksOnDistinctItalianDates() {
        stubRanking();

        var result = planner.plan(10, true);

        assertThat(result.slots()).extracting(slot -> slot.dateTime().toLocalDate()).doesNotHaveDuplicates();
        assertThat(result.slots().getFirst().dateTime().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        Map<LocalDate, Long> perWeek = result.slots().stream().collect(Collectors.groupingBy(
                slot -> slot.dateTime().toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(
                        java.time.DayOfWeek.MONDAY)), Collectors.counting()));
        assertThat(perWeek).containsExactlyInAnyOrderEntriesOf(Map.of(
                LocalDate.of(2026, 11, 30), 3L, LocalDate.of(2026, 12, 7), 3L,
                LocalDate.of(2026, 12, 14), 3L, LocalDate.of(2026, 12, 21), 1L));
    }

    @Test
    void countsExistingShortsDeduplicatesItsOwnUploadsAndSkipsLongVideoDay() {
        stubRanking();
        Instant scheduled = Instant.parse("2026-11-27T20:00:00Z");
        ShortDraft recorded = ShortDraft.create("Brief", "Title", "Description", scheduled, false, now);
        recorded.startUpload("Title", "Description", scheduled, false);
        recorded.finishUpload("alreadyScheduled");
        when(calendar.between(any(), any())).thenReturn(List.of(
                event("published", "2026-11-23T07:00:00Z", true),
                event("long", "2026-11-24T14:00:00Z", false),
                event("alreadyScheduled", scheduled.toString(), true)));
        when(drafts.findReservations(any(), any(), any())).thenReturn(List.of(recorded));

        var result = planner.plan(2, false);

        assertThat(result.slots()).extracting(slot -> slot.dateTime().toLocalDate())
                .containsExactly(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 30));
        verify(calendar).between(Instant.parse("2026-11-22T23:00:00Z"), now.plusSeconds(365L * 86400));
    }

    @Test
    void validatesAgainBeforeUploadAndRejectsTheFourthShortEvenOnAFreeDay() {
        when(calendar.between(Instant.parse("2026-11-22T23:00:00Z"), Instant.parse("2026-11-29T23:00:00Z")))
                .thenReturn(List.of(
                        event("monday", "2026-11-22T23:00:00Z", true),
                        event("wednesday", "2026-11-25T07:00:00Z", true),
                        event("sunday", "2026-11-29T22:59:00Z", true)));

        assertThatThrownBy(() -> planner.assertAvailable(Instant.parse("2026-11-26T20:00:00Z"), "draft"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tre Shorts");
    }

    @Test
    void rejectsElevenBeforeCallingYoutube() {
        assertThatThrownBy(() -> planner.plan(11, false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(calendar, history, drafts);
    }

    private void stubRanking() {
        when(history.publicationHistory(now)).thenReturn(new WeeklyShortPlanner.PublicationHistory(now, List.of(), false));
        when(history.rankSlots(any(), any())).thenAnswer(call -> {
            List<java.time.ZonedDateTime> times = call.getArgument(0);
            return times.stream().map(time -> new ShortPublicationPlanner.Slot(time.toOffsetDateTime(), 0)).toList();
        });
    }

    private static YouTubePublicationCalendar.Publication event(String id, String at, boolean countsAsShort) {
        return new YouTubePublicationCalendar.Publication(id, id, Instant.parse(at), true, countsAsShort);
    }
}
