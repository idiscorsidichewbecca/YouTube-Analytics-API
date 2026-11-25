package it.chewbecca.youtubeanalytics.performance;

import io.micrometer.core.instrument.MeterRegistry;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.exception.YoutubeNotConnectedException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class EndScreenCollector {
    private static final Logger log = LoggerFactory.getLogger(EndScreenCollector.class);
    private final YouTubeReportingClient api;
    private final EndScreenReportingRepository store;
    private final GoogleAccessTokenService tokens;
    private final YouTubeDataClient videos;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final boolean allowJobCreation;

    public EndScreenCollector(YouTubeReportingClient api, EndScreenReportingRepository store,
                              GoogleAccessTokenService tokens, YouTubeDataClient videos, Clock clock, MeterRegistry metrics,
                              @Value("${app.performance.end-screen-reporting-enabled:false}") boolean enabled,
                              @Value("${app.performance.reporting-create-job:false}") boolean allowJobCreation) {
        this.api = api;
        this.store = store;
        this.tokens = tokens;
        this.videos = videos;
        this.clock = clock;
        this.metrics = metrics;
        this.enabled = enabled;
        this.allowJobCreation = allowJobCreation;
    }

    @Scheduled(cron = "${app.performance.end-screen-reporting-cron:0 45 */6 * * *}", zone = "Europe/Rome")
    public synchronized void collect() {
        if (!enabled) return;
        try {
            var credential = tokens.requireCredential();
            var owner = credential.getChannelId();
            var type = EndScreenReportingRepository.TYPE;
            var jobs = api.jobs(type);
            if (jobs.isEmpty()) {
                if (allowJobCreation && api.supports(type)) api.createJob(type);
                return;
            }
            var current = videos.getAllUploadedVideos(credential.getUploadsPlaylistId()).stream()
                    .filter(video -> video.available() && owner.equals(video.channelId()))
                    .map(YouTubeDataClient.VideoMetadata::videoId).collect(Collectors.toSet());
            var newest = new TreeMap<LocalDate, YouTubeReportingClient.Report>();
            var order = Comparator.comparing(YouTubeReportingClient.Report::created)
                    .thenComparing(YouTubeReportingClient.Report::id);
            for (var job : jobs) {
                for (var report : api.reports(job)) newest.merge(report.day(), report,
                        (first, second) -> order.compare(first, second) >= 0 ? first : second);
            }
            for (var report : newest.values()) {
                if (store.isCurrent(owner, report)) continue;
                var rows = api.downloadEndScreens(owner, report).stream()
                        .filter(row -> current.contains(row.videoId())).toList();
                if (!owner.equals(tokens.requireCredential().getChannelId())) {
                    throw new IllegalStateException("Il canale OAuth è cambiato durante il download");
                }
                store.replace(owner, report, clock.instant(), rows);
                metrics.counter("chewbecca.performance.end.screens.imported").increment();
            }
            store.verify(owner, current, clock.instant());
        } catch (YoutubeNotConnectedException ignored) {
            log.info("end_screen_import outcome=NOT_CONNECTED");
        } catch (RuntimeException error) {
            metrics.counter("chewbecca.performance.end.screens.errors").increment();
            log.warn("end_screen_import outcome=ERROR exception={}", error.getClass().getSimpleName());
        }
    }
}
