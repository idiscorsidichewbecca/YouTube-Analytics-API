package it.chewbecca.youtubeanalytics.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoOptimizationReviewTest {
    @Test
    void groupsChangesOnTheSameDayAndMarksOverlapWithAnEarlierChange() {
        var repository = mock(VideoOptimizationRepository.class);
        var advisor = mock(PerformanceAdvisor.class);
        var clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);
        var service = new VideoOptimizationService(repository, advisor, null, null, null, null,
                new ObjectMapper(), clock, null, true);
        var title = draft("title", "TITLE", "2026-11-25T10:00:00Z");
        var thumbnail = draft("thumbnail", "THUMBNAIL", "2026-11-25T10:01:00Z");
        var previous = draft("older", "DESCRIPTION", "2026-11-23T10:00:00Z");
        when(advisor.channel()).thenReturn("owner");
        when(advisor.lastConservativeDay()).thenReturn(LocalDate.parse("2026-11-23"));
        when(repository.applied("owner", Instant.parse("2026-09-27T12:00:00Z")))
                .thenReturn(List.of(thumbnail, title, previous));
        when(repository.draft("owner", thumbnail.id())).thenReturn(thumbnail);

        var result = service.reviewRecent(1, 1, false);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        var intervention = result.items().getFirst();
        assertThat(intervention.changes()).extracting(VideoOptimizationService.Change::action)
                .containsExactly("TITLE", "THUMBNAIL");
        assertThat(intervention.overlappingChangeIds()).containsExactly("older");
        assertThat(intervention.comparison().status()).isEqualTo("WAITING_FOR_DATA");
        assertThat(intervention.comparison().before().to()).isEqualTo(LocalDate.parse("2026-11-24"));
        assertThat(intervention.comparison().after().from()).isEqualTo(LocalDate.parse("2026-11-26"));
    }

    private static VideoOptimizationRepository.Draft draft(String id, String action, String timestamp) {
        var applied = Instant.parse(timestamp);
        return new VideoOptimizationRepository.Draft(id, "owner", "ABCDEFGHIJK", action, "APPLIED", 1,
                "motivazione", "prima", "dopo", applied, applied, null, "[]");
    }
}
