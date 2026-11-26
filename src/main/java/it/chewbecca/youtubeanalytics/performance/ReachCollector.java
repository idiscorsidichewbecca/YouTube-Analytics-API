package it.chewbecca.youtubeanalytics.performance;

import io.micrometer.core.instrument.MeterRegistry;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.exception.YoutubeNotConnectedException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ReachCollector {
    private static final Logger log = LoggerFactory.getLogger(ReachCollector.class);
    private final YouTubeReportingClient reporting;
    private final ReachRepository reach;
    private final GoogleAccessTokenService tokens;
    private final YouTubeDataClient data;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final boolean allowJobCreation;
    private volatile Status last = new Status("NOT_RUN", null, null, null, null, 0);

    public ReachCollector(YouTubeReportingClient reporting, ReachRepository reach, GoogleAccessTokenService tokens,
                          YouTubeDataClient data, Clock clock, MeterRegistry metrics,
                          @Value("${app.performance.reporting-enabled:false}") boolean enabled,
                          @Value("${app.performance.reporting-create-job:false}") boolean allowJobCreation) {
        this.reporting = reporting;
        this.reach = reach;
        this.tokens = tokens;
        this.data = data;
        this.clock = clock;
        this.metrics = metrics;
        this.enabled = enabled;
        this.allowJobCreation = allowJobCreation;
    }

    @Scheduled(cron = "${app.performance.reporting-cron:0 20 */6 * * *}", zone = "Europe/Rome")
    public synchronized void collect() {
        if (!enabled) return;
        Instant now = clock.instant();
        String channel = null;
        int imported = 0;
        try {
            var credential = tokens.requireCredential();
            channel = credential.getChannelId();
            var jobs = reporting.jobs(YouTubeReportingClient.TYPE);
            if (jobs.isEmpty()) {
                if (!reporting.supports(YouTubeReportingClient.TYPE)) {
                    last = new Status("REPORT_TYPE_UNAVAILABLE", channel, now, null, null, 0);
                    return;
                }
                if (!allowJobCreation) {
                    last = new Status("JOB_CREATION_DISABLED", channel, now, null, null, 0);
                    return;
                }
                String job = reporting.createJob(YouTubeReportingClient.TYPE);
                last = new Status("WAITING_FOR_REPORTS", channel, now, job, null, 0);
                return;
            }
            var newest = reporting.latestReports(jobs);
            String owner = channel;
            var existing = data.getAllUploadedVideos(credential.getUploadsPlaylistId()).stream()
                    .filter(video -> video.available() && owner.equals(video.channelId()))
                    .map(YouTubeDataClient.VideoMetadata::videoId).collect(Collectors.toSet());
            for (var report : newest) {
                if (reach.isCurrent(channel, report.day(), report.created(), report.id())) continue;
                var rows = reporting.download(channel, report).stream()
                        .filter(row -> existing.contains(row.videoId())).toList();
                if (!channel.equals(tokens.requireCredential().getChannelId())) {
                    throw new IllegalStateException("Il canale OAuth è cambiato durante il download");
                }
                reach.replace(channel, report.day(), report.created(), report.id(), now, rows);
                imported++;
            }
            reach.removeMissingVideos(channel, existing);
            reach.verified(channel, now);
            last = new Status(newest.isEmpty() ? "WAITING_FOR_REPORTS" : "OK", channel,
                    now, String.join(",", jobs), newest.isEmpty() ? null : newest.getLast().day(), imported);
            metrics.counter("chewbecca.performance.reporting.imported").increment(imported);
            log.info("reach_import result={} days_imported={}", last.state(), imported);
        } catch (YoutubeNotConnectedException error) {
            last = new Status("YOUTUBE_NOT_CONNECTED", channel, now, null, null, imported);
        } catch (RuntimeException error) {
            last = new Status("ERROR", channel, now, null, null, imported);
            metrics.counter("chewbecca.performance.reporting.errors").increment();
            log.warn("reach_import result=ERROR days_imported={} exception={}", imported, error.getClass().getSimpleName());
        }
    }

    public Status status(String channel) {
        if (!enabled) return new Status("DISABLED", null, null, null, null, 0);
        return Objects.equals(last.channel(), channel) ? last : new Status("NOT_RUN", channel, null, null, null, 0);
    }

    public record Status(String state, String channel, Instant checkedAt, String jobId, LocalDate latestDay,
                         int importedOnLastRun) {}
}
