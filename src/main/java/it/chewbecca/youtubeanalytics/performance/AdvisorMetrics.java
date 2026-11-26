package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient.AdvisorReport;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient.VideoMetadata;
import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static it.chewbecca.youtubeanalytics.performance.ExtendedReportingRepository.ReachGroup;
import static it.chewbecca.youtubeanalytics.performance.PerformanceAdvisor.Observation;
import static it.chewbecca.youtubeanalytics.performance.PerformanceAdvisor.Window;

@Service
public class AdvisorMetrics {
    static final List<String> NOTES = List.of(
            "Analytics usa giorni Pacific locali; Reporting usa giorni PST fissi. Ultima data esposta solo se osservabile.",
            "SHORTS_FEED indica visualizzazioni sul player del feed. Shown in feed, viewed vs swiped away e spettatori di ritorno di Studio non sono esposti dai report pubblici usati.",
            "Le statistiche Data API sono cumulative. I risultati per Paese, demografia e sorgente possono essere omessi o aggregati da Google.",
            "Le percentuali editoriali sono in scala 0–100; il CTR delle miniature dal CSV è un rapporto 0–1. Gli importi Analytics sono in USD.");
    private static final Map<String, ReachGroup> REACH_GROUPS = Map.of(
            "REACH_BY_SOURCE", ReachGroup.SOURCE, "REACH_BY_SOURCE_DETAIL", ReachGroup.SOURCE_DETAIL,
            "REACH_BY_DEVICE", ReachGroup.DEVICE, "REACH_BY_OS", ReachGroup.OS);
    private static final List<String> VIDEO_DEFAULT = List.of("CORE", "ENGAGEMENT", "DAILY", "REACH", "TRAFFIC");
    private static final List<String> CHANNEL_DEFAULT = List.of("CORE", "ENGAGEMENT", "DAILY", "REACH", "TRAFFIC");
    private final YouTubeAnalyticsClient analytics;
    private final YouTubeDataClient data;
    private final GoogleAccessTokenService tokens;
    private final ReachRepository reach;
    private final EndScreenReportingRepository endScreens;
    private final ExtendedReportingRepository extended;
    private final Clock clock;

    public AdvisorMetrics(YouTubeAnalyticsClient analytics, YouTubeDataClient data, GoogleAccessTokenService tokens,
                          ReachRepository reach, EndScreenReportingRepository endScreens,
                          ExtendedReportingRepository extended, Clock clock) {
        this.analytics = analytics;
        this.data = data;
        this.tokens = tokens;
        this.reach = reach;
        this.endScreens = endScreens;
        this.extended = extended;
        this.clock = clock;
    }

    public static List<String> sections(boolean channel) {
        var sections = new ArrayList<>(Arrays.stream(AdvisorReport.values())
                .filter(report -> !channel || report.channelSupported()).map(Enum::name).toList());
        sections.addAll(REACH_GROUPS.keySet().stream().sorted().toList());
        sections.addAll(List.of("REACH", "END_SCREENS", "END_SCREENS_BY_TYPE", "SUBTITLES", "SHORTS_FEED", "RATIOS"));
        if (!channel) sections.add("LIFETIME_COUNTERS");
        return List.copyOf(sections);
    }

    Map<String, Observation> read(String owner, VideoMetadata video, String format, Window window, List<String> requested) {
        boolean channel = video == null;
        var chosen = requested.isEmpty() ? channel ? CHANNEL_DEFAULT : VIDEO_DEFAULT : requested;
        if (!sections(channel).containsAll(chosen)) throw new IllegalArgumentException("Sezione metriche non valida per questo strumento");
        Map<String, Observation> results = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> queryResults = new HashMap<>();
        String id = channel ? null : video.videoId();
        for (String section : chosen.stream().distinct().toList()) {
            var reachGroup = REACH_GROUPS.get(section);
            if (reachGroup != null) {
                var breakdown = extended.reach(owner, id, window.from(), window.to(), reachGroup);
                results.put(section, reporting(window, breakdown));
                continue;
            }
            var observation = switch (section) {
                case "REACH" -> {
                    var totals = reach.reach(owner, id, window.from(), window.to());
                    LocalDate last = totals.daily().isEmpty() ? null : totals.daily().getLast().date();
                    yield new Observation(totals.source(), window, clock.instant(), last, totals.availability(), totals);
                }
                case "END_SCREENS" -> {
                    var totals = endScreens.totals(owner, id, window.from(), window.to());
                    LocalDate last = totals.daily().isEmpty() ? null : totals.daily().getLast().date();
                    yield new Observation("YouTube Reporting API/channel_end_screens_a2", window, clock.instant(), last,
                            totals.availability(), totals);
                }
                case "END_SCREENS_BY_TYPE" -> {
                    var types = endScreens.byType(owner, id, window.from(), window.to());
                    yield new Observation("YouTube Reporting API/channel_end_screens_a2", window, clock.instant(),
                            types.lastObservedDay(), types.availability(), types);
                }
                case "SUBTITLES" -> reporting(window, extended.subtitles(owner, id, window.from(), window.to()));
                case "LIFETIME_COUNTERS" -> {
                    var counters = data.getOwnedVideoCounters(id, owner);
                    yield new Observation("YouTube Data API/videos.list", null, clock.instant(), null,
                            counters.isEmpty() ? "NO_ROWS" : "RETURNED", counters.isEmpty() ? null : counters);
                }
                case "SHORTS_FEED" -> optional(window, () -> rows(queryResults, id, window, "PLAYBACK_LOCATION").stream()
                        .filter(row -> "SHORTS_FEED".equals(row.get("insightPlaybackLocationType"))).toList());
                case "RETENTION" -> optional(window, () -> {
                    var rows = rows(queryResults, id, window, section);
                    return rows.isEmpty() ? List.of() : PerformanceAdvisor.retention(rows, video.durationSeconds());
                });
                case "RETENTION_SEGMENTS" -> optional(window, () -> rows(queryResults, id, window, section).stream()
                        .map(row -> retentionSegment(row, video.durationSeconds())).toList());
                case "RATIOS" -> optional(window, () -> ratios(first(rows(queryResults, id, window, "CORE")),
                        first(rows(queryResults, id, window, "ENGAGEMENT"))));
                case "MONETARY", "MONETARY_DAILY", "MONETARY_COMPONENTS", "ADS", "ADS_BY_TYPE" -> {
                    if (!hasMonetaryScope()) yield unavailable(window, "UNAUTHORIZED");
                    yield optional(window, () -> {
                        var rows = rows(queryResults, id, window, section);
                        return id != null && (section.equals("MONETARY") || section.equals("MONETARY_DAILY"))
                                ? rows.stream().map(row -> withRpm(row, "SHORT".equals(format))).toList() : rows;
                    });
                }
                default -> optional(window, () -> rows(queryResults, id, window, section));
            };
            results.put(section, observation);
        }
        return results;
    }

    private Observation reporting(Window window, ExtendedReportingRepository.Breakdown<?> data) {
        var last = data.reports().isEmpty() ? null : data.reports().getLast().date();
        return new Observation("YouTube Reporting API/" + data.reportType(), window, clock.instant(), last,
                data.availability(), data);
    }

    private boolean hasMonetaryScope() {
        String scopes = tokens.requireCredential().getScopes();
        return scopes != null && Arrays.asList(scopes.split("\\s+")).contains(YoutubeOAuthService.MONETARY_ANALYTICS_SCOPE);
    }

    private List<Map<String, Object>> rows(Map<String, List<Map<String, Object>>> cache, String id, Window window, String section) {
        return cache.computeIfAbsent(section, key -> analytics.advisorRows(id, window.from(), window.to(), key));
    }

    Observation optional(Window window, Supplier<?> get) {
        try {
            Object data = get.get();
            boolean empty = data instanceof Collection<?> collection && collection.isEmpty()
                    || data instanceof Map<?, ?> map && map.isEmpty();
            LocalDate last = latestDay(data);
            String availability = empty ? "NO_ROWS" : last != null && last.isBefore(window.to()) ? "PARTIAL"
                    : last == null && window.to().isAfter(LocalDate.now(clock.withZone(PerformanceAdvisor.PACIFIC)).minusDays(3))
                    ? "MAY_BE_PARTIAL" : "RETURNED";
            return new Observation("YouTube Analytics API", window, clock.instant(), last, availability, empty ? null : data);
        } catch (GoogleApiException error) {
            if (error.getStatusCode() == 400 || (error.getStatusCode() == 403 && !YouTubeStudioClient.quota(error))) {
                return unavailable(window, error.getStatusCode() == 403 ? "FORBIDDEN" : "UNAVAILABLE_OR_UNSUPPORTED");
            }
            throw error;
        }
    }

    private Observation unavailable(Window window, String state) {
        return new Observation("YouTube Analytics API", window, clock.instant(), null, state, null);
    }

    private static LocalDate latestDay(Object result) {
        if (!(result instanceof List<?> rows)) return null;
        return rows.stream().filter(Map.class::isInstance).map(row -> ((Map<?, ?>) row).get("day"))
                .filter(String.class::isInstance).map(day -> LocalDate.parse((String) day))
                .max(LocalDate::compareTo).orElse(null);
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    static Map<String, Object> ratios(Map<String, Object> core, Map<String, Object> engagement) {
        var observations = new LinkedHashMap<String, Object>();
        var views = number(core, "views");
        var gained = number(engagement, "subscribersGained");
        var lost = number(engagement, "subscribersLost");
        if (gained != null && lost != null) observations.put("netSubscribers", gained.longValue() - lost.longValue());
        if (views == null || views.doubleValue() <= 0) return observations;
        var interactions = List.of("likes", "comments", "shares").stream().map(key -> number(engagement, key)).toList();
        if (interactions.stream().allMatch(Objects::nonNull)) {
            observations.put("engagementPercent", interactions.stream().mapToDouble(Number::doubleValue).sum()
                    * 100.0 / views.doubleValue());
        }
        if (gained != null) observations.put("subscriberConversionPercent", gained.doubleValue() * 100.0 / views.doubleValue());
        return observations;
    }

    static Map<String, Object> withRpm(Map<String, Object> row, boolean shorts) {
        var revenue = number(row, "estimatedRevenue");
        var denominatorName = shorts ? "engagedViews" : "views";
        var denominator = number(row, denominatorName);
        if (revenue == null || denominator == null || denominator.doubleValue() <= 0) return row;
        var extended = new LinkedHashMap<>(row);
        extended.put("estimatedRpmUsd", revenue.doubleValue() * 1000 / denominator.doubleValue());
        extended.put("estimatedRpmDenominator", denominatorName);
        return extended;
    }

    private static Map<String, Object> retentionSegment(Map<String, Object> row, long duration) {
        var progress = number(row, "elapsedVideoTimeRatio");
        if (progress == null || duration <= 0) return row;
        var extended = new LinkedHashMap<>(row);
        extended.put("elapsedSeconds", progress.doubleValue() * duration);
        return extended;
    }

    private static Number number(Map<String, Object> values, String key) {
        return values.get(key) instanceof Number number ? number : null;
    }
}
