package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.community.CommunityReplyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@Import({ReachRepository.class, CommunityReplyRepository.class, VideoOptimizationRepository.class, EndScreenReportingRepository.class})
class StudioPersistenceTest {
    @Autowired ReachRepository reach;
    @Autowired EndScreenReportingRepository screens;
    @Autowired CommunityReplyRepository replies;
    @Autowired VideoOptimizationRepository edits;
    private final Instant now = Instant.parse("2026-11-25T10:00:00Z");
    private final LocalDate day = LocalDate.parse("2026-11-22");

    @Test
    void importsCsvAndReplacesOldDayAtomicallyWithLatestEdition() throws Exception {
        var rows = ReachCsv.read(new StringReader("""
                date,channel_id,video_id,video_thumbnail_impressions,video_thumbnail_impressions_ctr
                "20261122",owner,ABCDEFGHIJK,200,0.12
                """), "owner", day);
        reach.replace("owner", day, now, "a", now, rows);
        reach.replace("owner", day, now.minusSeconds(1), "older", now, List.of());
        var availability = reach.reach("owner", "ABCDEFGHIJK", day, day.plusDays(1));
        assertThat(availability.impressions()).isEqualTo(200);
        assertThat(availability.ctr()).isEqualTo(0.12);
        assertThat(availability.availability()).isEqualTo("PARTIAL");
        reach.replace("owner", day, now.plusSeconds(3), "revision", now, List.of());
        assertThat(reach.reach("owner", "ABCDEFGHIJK", day, day).impressions()).isNull();
    }

    @Test
    void locksSameParentAcrossBatchesAndRejectsStaleApproval() {
        var item = new CommunityReplyRepository.Item(1, "thread", "c", "root", "ABCDEFGHIJK", "video",
                "author", "original", "reply", "MEDIUM", null, "DRAFT", null);
        var first = replies.create("owner", now, List.of(item));
        var second = replies.create("owner", now, List.of(item));
        var edit = new CommunityReplyRepository.Edit(1, "APPROVE", null);
        first = replies.revise("owner", first.id(), 1, List.of(edit));
        second = replies.revise("owner", second.id(), 1, List.of(edit));
        assertThat(replies.claim("owner", first.id(), 2, first.items().getFirst())).isTrue();
        assertThat(replies.claim("owner", second.id(), 2, second.items().getFirst())).isFalse();
        assertThat(replies.get("owner", first.id()).items().getFirst().status()).isEqualTo("SENDING");
    }

    @Test
    void locksDistinctActionsForSameVideoWhileWriteIsInFlight() {
        var title = edits.create(draft("1", "TITLE"), List.of());
        var description = edits.create(draft("2", "DESCRIPTION"), List.of());
        assertThat(edits.claim(title, "new title", now)).isTrue();
        assertThat(edits.claim(description, "new description", now)).isFalse();
        edits.finish(title, "APPLIED", "new title", now, null, true);
        assertThat(edits.claim(description, "new description", now)).isTrue();
        assertThat(edits.history("owner", "ABCDEFGHIJK")).hasSize(2);
    }

    @Test
    void aggregatesEndScreensAcrossElementsAndRetainsUpstreamMetadata() throws Exception {
        var rows = EndScreenReportingRepository.parse(new StringReader("""
                date,channel_id,video_id,end_screen_element_type,end_screen_element_impressions,end_screen_element_clicks
                20261122,owner,ABCDEFGHIJK,video,100,5
                20261122,owner,ABCDEFGHIJK,subscribe,200,25
                """), "owner", day);
        var report = new YouTubeReportingClient.Report("end-screens", day, now, "unused");
        screens.replace("owner", report, now, rows);
        var totals = screens.totals("owner", "ABCDEFGHIJK", day, day);
        assertThat(totals.endScreenElementImpressions()).isEqualTo(300);
        assertThat(totals.endScreenElementClicks()).isEqualTo(30);
        assertThat(totals.endScreenElementClickRate()).isEqualTo(.1);
        assertThat(totals.availability()).isEqualTo("AVAILABLE");
        assertThat(totals.daily().getFirst().upstreamCreatedAt().toInstant()).isEqualTo(now);
    }

    private VideoOptimizationRepository.Draft draft(String id, String kind) {
        return new VideoOptimizationRepository.Draft(id, "owner", "ABCDEFGHIJK", kind, "READY", 1, "evidence",
                "old", null, now, null, null, "[]");
    }
}
