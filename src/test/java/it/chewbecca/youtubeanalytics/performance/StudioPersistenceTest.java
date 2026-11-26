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
@Import({ReachRepository.class, CommunityReplyRepository.class, VideoOptimizationRepository.class, EndScreenReportingRepository.class, ExtendedReportingRepository.class})
class StudioPersistenceTest {
    @Autowired ReachRepository reach;
    @Autowired EndScreenReportingRepository screens;
    @Autowired ExtendedReportingRepository extended;
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
        assertThat(screens.totals("owner", null, day, day).endScreenElementClicks()).isEqualTo(30);
        assertThat(screens.byType("owner", "ABCDEFGHIJK", day, day).elements())
                .extracting(EndScreenReportingRepository.Element::clickRate).containsExactly(.125, .05);
    }

    @Test
    void weightsSegmentedCtrOnlyOverMeasuredImpressionsAndReplacesCorrectedDays() throws Exception {
        var rows = ExtendedReportingCsv.reach(new StringReader("""
                date,channel_id,video_id,traffic_source_type,traffic_source_detail,device_type,operating_system,video_thumbnail_impressions,video_thumbnail_impressions_ctr
                20261122,owner,ABCDEFGHIJK,5,"Abc, def",100,200,300,0.10
                20261122,owner,ABCDEFGHIJK,5,"Abc, def",101,201,100,0.50
                20261122,owner,ABCDEFGHIJK,5,NULL,101,201,100,
                20261122,owner,aBcDEfghIJK,5,elsewhere,100,200,100,0.1
                """), "owner", day);
        var report = new YouTubeReportingClient.Report("initial", day, now, "unused");
        extended.replaceReach("owner", report, now, rows);
        var result = extended.reach("owner", "ABCDEFGHIJK", day, day.plusDays(1),
                ExtendedReportingRepository.ReachGroup.SOURCE);
        assertThat(result.availability()).isEqualTo("PARTIAL");
        assertThat(result.segments()).containsExactly(new ExtendedReportingRepository.ReachSegment("5", null, 500, 400, .2));
        var detail = extended.reach("owner", "ABCDEFGHIJK", day, day, ExtendedReportingRepository.ReachGroup.SOURCE_DETAIL);
        assertThat(detail.segments()).extracting(ExtendedReportingRepository.ReachSegment::label)
                .containsExactly("Abc, def", null);
        assertThat(extended.reach("owner", null, day, day, ExtendedReportingRepository.ReachGroup.DEVICE)
                .segments()).hasSize(2);

        extended.replaceReach("owner", new YouTubeReportingClient.Report("corrected", day, now.plusSeconds(1), "unused"),
                now, List.of());
        extended.replaceReach("owner", report, now, rows);
        assertThat(extended.reach("owner", "ABCDEFGHIJK", day, day, ExtendedReportingRepository.ReachGroup.SOURCE)
                .availability()).isEqualTo("NO_ROWS");
    }

    @Test
    void distinguishesMissingSubtitleMetricFromMeasuredZeroAndRemovesDeletedVideos() throws Exception {
        var rows = ExtendedReportingCsv.subtitles(new StringReader("""
                date,channel_id,video_id,subtitle_language,subtitle_language_autotranslated,engaged_views,views,watch_time_minutes,red_views,red_watch_time_minutes
                20261122,owner,ABCDEFGHIJK,it,,0,120,30.5,,0
                20261122,owner,ABCDEFGHIJK,it,en,15,20,5,0,0
                """), "owner", day);
        var report = new YouTubeReportingClient.Report("subs", day, now, "unused");
        extended.replaceSubtitles("owner", report, now, rows);
        var result = extended.subtitles("owner", "ABCDEFGHIJK", day, day);
        assertThat(result.segments()).containsExactly(
                new ExtendedReportingRepository.SubtitleSegment("it", null, 0L, 120L, 30.5, null, 0.0),
                new ExtendedReportingRepository.SubtitleSegment("it", "en", 15L, 20L, 5.0, 0L, 0.0));
        extended.verify("owner", ExtendedReportingRepository.SUBTITLES, java.util.Set.of(), now);
        assertThat(extended.subtitles("owner", "ABCDEFGHIJK", day, day).availability()).isEqualTo("NO_ROWS");
    }

    @Test
    void channelReachKeepsMissingCtrOutOfTheDenominator() {
        reach.replace("owner", day, now, "reach", now, List.of(
                new ReachCsv.VideoDay("ABCDEFGHIJK", 100, .25),
                new ReachCsv.VideoDay("aBcDEfghIJK", 20, null)));
        var totals = reach.reach("owner", null, day, day);
        assertThat(totals.impressions()).isEqualTo(120);
        assertThat(totals.impressionsWithCtr()).isEqualTo(100);
        assertThat(totals.ctr()).isEqualTo(.25);
        assertThat(totals.ctrAvailability()).isEqualTo("PARTIAL");
    }

    private VideoOptimizationRepository.Draft draft(String id, String kind) {
        return new VideoOptimizationRepository.Draft(id, "owner", "ABCDEFGHIJK", kind, "READY", 1, "evidence",
                "old", null, now, null, null, "[]");
    }
}
