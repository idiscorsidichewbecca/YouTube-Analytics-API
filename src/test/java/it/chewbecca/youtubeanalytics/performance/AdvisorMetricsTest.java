package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AdvisorMetricsTest {
    private static final String VIDEO = "ABCDEFGHIJK";
    private static final PerformanceAdvisor.Window WINDOW = new PerformanceAdvisor.Window(
            LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-25"));
    private MockRestServiceServer server;
    private AdvisorMetrics metrics;

    @BeforeEach
    void createMetrics() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(GoogleAccessTokenService.class);
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        var credential = mock(YoutubeCredential.class);
        when(tokens.requireCredential()).thenReturn(credential);
        metrics = new AdvisorMetrics(new YouTubeAnalyticsClient(builder.build(), tokens), mock(YouTubeDataClient.class),
                tokens, mock(ReachRepository.class), mock(EndScreenReportingRepository.class),
                mock(ExtendedReportingRepository.class), Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void readsGranularRetentionFromTheOfficialVideoReport() {
        var video = mock(YouTubeDataClient.VideoMetadata.class);
        when(video.videoId()).thenReturn(VIDEO);
        when(video.durationSeconds()).thenReturn(60L);
        server.expect(queryParam("dimensions", "elapsedVideoTimeRatio"))
                .andExpect(queryParam("metrics", "startedWatching,stoppedWatching,totalSegmentImpressions"))
                .andExpect(queryParam("filters", "video%3D%3D" + VIDEO))
                .andRespond(withSuccess("""
                        {"columnHeaders":[{"name":"elapsedVideoTimeRatio"},{"name":"startedWatching"},
                        {"name":"stoppedWatching"},{"name":"totalSegmentImpressions"}],"rows":[[0.25,0,8,40]]}
                        """, MediaType.APPLICATION_JSON));

        var result = metrics.read("owner", video, "SHORT", WINDOW, List.of("RETENTION_SEGMENTS")).get("RETENTION_SEGMENTS");
        assertThat(result.data()).isEqualTo(List.of(Map.of("elapsedVideoTimeRatio", .25,
                "elapsedSeconds", 15.0, "startedWatching", 0, "stoppedWatching", 8, "totalSegmentImpressions", 40)));
        server.verify();
    }

    @Test
    void reusesPlaybackQueryAndKeepsShortsFeedDistinctFromHome() {
        server.expect(requestTo(startsWith("https://youtubeanalytics.googleapis.com/v2/reports?")))
                .andExpect(queryParam("ids", "channel%3D%3DMINE"))
                .andExpect(queryParam("dimensions", "insightPlaybackLocationType"))
                .andExpect(queryParam("metrics", "engagedViews,views,estimatedMinutesWatched"))
                .andRespond(withSuccess("""
                        {"columnHeaders":[{"name":"insightPlaybackLocationType"},{"name":"engagedViews"},{"name":"views"}],
                        "rows":[["SHORTS_FEED",120,220],["BROWSE",5,10]]}
                        """, MediaType.APPLICATION_JSON));
        var results = metrics.read("owner", null, null, WINDOW, List.of("SHORTS_FEED", "PLAYBACK_LOCATION", "ADS"));
        assertThat(results.get("SHORTS_FEED").data()).isEqualTo(List.of(Map.of("insightPlaybackLocationType",
                "SHORTS_FEED", "engagedViews", 120, "views", 220)));
        assertThat(results.get("ADS").availability()).isEqualTo("UNAUTHORIZED");
        server.verify();
    }

    @Test
    void reportsUnsupportedMetricsAndIncompleteDailySeriesIndependently() {
        server.expect(queryParam("metrics", "uniques")).andRespond(withBadRequest());
        server.expect(queryParam("metrics", "engagedViews")).andRespond(withSuccess("""
                {"columnHeaders":[{"name":"day"},{"name":"engagedViews"}],"rows":[["2026-11-23",0]]}
                """, MediaType.APPLICATION_JSON));
        var result = metrics.read("owner", null, null, WINDOW, List.of("UNIQUE_VIEWERS_DAILY", "ENGAGED_VIEWS_DAILY"));
        assertThat(result.get("UNIQUE_VIEWERS_DAILY").availability()).isEqualTo("UNAVAILABLE_OR_UNSUPPORTED");
        assertThat(result.get("ENGAGED_VIEWS_DAILY").availability()).isEqualTo("PARTIAL");
        assertThat(result.get("ENGAGED_VIEWS_DAILY").lastObservedDay()).isEqualTo(LocalDate.parse("2026-11-23"));
        server.verify();
    }

    @Test
    void usesEngagedViewsForShortsRpmAndRequiresSupportedDenominators() {
        var source = Map.<String, Object>of("views", 1000L, "engagedViews", 250L, "estimatedRevenue", 1.5);
        assertThat(AdvisorMetrics.withRpm(source, true)).containsEntry("estimatedRpmUsd", 6.0);
        assertThat(AdvisorMetrics.withRpm(source, false)).containsEntry("estimatedRpmUsd", 1.5);
        assertThat(AdvisorMetrics.withRpm(Map.of("views", 1000L, "estimatedRevenue", 1.5), true))
                .doesNotContainKey("estimatedRpmUsd");
        assertThat(AdvisorMetrics.ratios(Map.of("views", 100), Map.of("likes", 5, "comments", 4, "shares", 1,
                "subscribersGained", 3, "subscribersLost", 4))).containsEntry("engagementPercent", 10.0)
                .containsEntry("subscriberConversionPercent", 3.0).containsEntry("netSubscribers", -1L);
    }
}
