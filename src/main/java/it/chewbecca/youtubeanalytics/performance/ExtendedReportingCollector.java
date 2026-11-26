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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static it.chewbecca.youtubeanalytics.performance.ExtendedReportingRepository.REACH;
import static it.chewbecca.youtubeanalytics.performance.ExtendedReportingRepository.SUBTITLES;

@Component
public class ExtendedReportingCollector {
    private static final Logger log = LoggerFactory.getLogger(ExtendedReportingCollector.class);
    private static final List<String> TYPES = List.of(REACH, SUBTITLES);
    private final YouTubeReportingClient reporting;
    private final ExtendedReportingRepository repository;
    private final GoogleAccessTokenService tokens;
    private final YouTubeDataClient videos;
    private final MeterRegistry meters;
    private final Clock clock;
    private final boolean enabled;
    private final boolean createJobs;
    private final Map<String, ReachCollector.Status> states = new ConcurrentHashMap<>();

    public ExtendedReportingCollector(YouTubeReportingClient reporting, ExtendedReportingRepository repository,
                                      GoogleAccessTokenService tokens, YouTubeDataClient videos, Clock clock,
                                      MeterRegistry meters, @Value("${app.performance.extended-reporting-enabled:false}") boolean enabled,
                                      @Value("${app.performance.reporting-create-job:false}") boolean createJobs) {
        this.reporting = reporting;
        this.repository = repository;
        this.tokens = tokens;
        this.videos = videos;
        this.clock = clock;
        this.meters = meters;
        this.enabled = enabled;
        this.createJobs = createJobs;
    }

    @Scheduled(cron = "${app.performance.extended-reporting-cron:0 35 */6 * * *}", zone = "Europe/Rome")
    public synchronized void collect() {
        if (!enabled) return;
        var now = clock.instant();
        String channel = null;
        try {
            var credential = tokens.requireCredential();
            var owner = credential.getChannelId();
            channel = owner;
            var owned = videos.getAllUploadedVideos(credential.getUploadsPlaylistId()).stream()
                    .filter(video -> video.available() && owner.equals(video.channelId()))
                    .map(YouTubeDataClient.VideoMetadata::videoId).collect(Collectors.toSet());
            for (String type : TYPES) collect(type, owner, owned, now);
        } catch (YoutubeNotConnectedException ignored) {
            states.clear();
        } catch (RuntimeException error) {
            var status = new ReachCollector.Status("ERROR", channel, now, null, null, 0);
            for (String type : TYPES) states.put(type, status);
            log.warn("extended_reporting_catalog result=ERROR exception={}", error.getClass().getSimpleName());
        }
    }

    private void collect(String type, String owner, Set<String> owned, Instant now) {
        int imported = 0;
        try {
            var jobs = reporting.jobs(type);
            if (jobs.isEmpty()) {
                if (!reporting.supports(type)) {
                    states.put(type, new ReachCollector.Status("REPORT_TYPE_UNAVAILABLE", owner, now, null, null, 0));
                } else if (!createJobs) {
                    states.put(type, new ReachCollector.Status("JOB_CREATION_DISABLED", owner, now, null, null, 0));
                } else {
                    states.put(type, new ReachCollector.Status("WAITING_FOR_REPORTS", owner, now,
                            reporting.createJob(type), null, 0));
                }
                return;
            }
            requireChannel(owner);
            repository.verify(owner, type, owned, now);
            var reports = reporting.latestReports(jobs);
            for (var report : reports) {
                if (repository.isCurrent(owner, type, report)) continue;
                if (REACH.equals(type)) {
                    var rows = reporting.downloadSegmentedReach(owner, report).stream()
                            .filter(row -> owned.contains(row.video())).toList();
                    requireChannel(owner);
                    repository.replaceReach(owner, report, now, rows);
                } else {
                    var rows = reporting.downloadSubtitles(owner, report).stream()
                            .filter(row -> owned.contains(row.video())).toList();
                    requireChannel(owner);
                    repository.replaceSubtitles(owner, report, now, rows);
                }
                imported++;
                meters.counter("chewbecca.performance.extended_reporting.imported", "report", type).increment();
            }
            states.put(type, new ReachCollector.Status(reports.isEmpty() ? "WAITING_FOR_REPORTS" : "OK", owner,
                    now, String.join(",", jobs), reports.isEmpty() ? null : reports.getLast().day(), imported));
        } catch (RuntimeException error) {
            states.put(type, new ReachCollector.Status("ERROR", owner, now, null, null, imported));
            meters.counter("chewbecca.performance.extended_reporting.errors", "report", type).increment();
            log.warn("extended_reporting result=ERROR report={} days_imported={} exception={}", type, imported,
                    error.getClass().getSimpleName());
        }
    }

    private void requireChannel(String owner) {
        if (!owner.equals(tokens.requireCredential().getChannelId())) {
            throw new IllegalStateException("Il canale OAuth è cambiato durante il download");
        }
    }

    public Map<String, ReachCollector.Status> status(String channel) {
        return TYPES.stream().collect(Collectors.toUnmodifiableMap(type -> type, type -> {
            if (!enabled) return new ReachCollector.Status("DISABLED", null, null, null, null, 0);
            var status = states.get(type);
            return status != null && channel.equals(status.channel()) ? status
                    : new ReachCollector.Status("NOT_RUN", channel, null, null, null, 0);
        }));
    }
}
