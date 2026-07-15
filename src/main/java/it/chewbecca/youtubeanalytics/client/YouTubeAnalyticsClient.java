package it.chewbecca.youtubeanalytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class YouTubeAnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAnalyticsClient.class);

    private static final String REPORTS_URL = "https://youtubeanalytics.googleapis.com/v2/reports";

    /*
     * Le metriche sono intenzionalmente separate in più query.
     * YouTube Analytics applica regole di compatibilità tra famiglie di report e,
     * in alcuni casi, rifiuta con HTTP 400 una query che accorpa tutte le metriche.
     */
    private static final String CORE_VIEW_METRICS = String.join(",",
            "views",
            "estimatedMinutesWatched",
            "averageViewDuration",
            "averageViewPercentage"
    );
    private static final String ENGAGEMENT_METRICS = String.join(",",
            "comments",
            "likes",
            "shares",
            "subscribersGained",
            "subscribersLost"
    );
    private static final String ENGAGED_VIEWS_METRIC = "engagedViews";
    private static final int VIDEO_BATCH_SIZE = 100;

    private final RestClient restClient;
    private final GoogleAccessTokenService accessTokenService;

    public YouTubeAnalyticsClient(RestClient googleRestClient, GoogleAccessTokenService accessTokenService) {
        this.restClient = googleRestClient;
        this.accessTokenService = accessTokenService;
    }

    public AnalyticsMetrics getChannelAggregate(LocalDate startDate, LocalDate endDate) {
        AnalyticsMetrics core = firstMetrics(query(new AnalyticsQuery(
                startDate, endDate, CORE_VIEW_METRICS, null, null, null, null, null
        )));
        AnalyticsMetrics engagement = firstMetrics(query(new AnalyticsQuery(
                startDate, endDate, ENGAGEMENT_METRICS, null, null, null, null, null
        )));
        AnalyticsMetrics engagedViews = firstMetrics(queryOptional(new AnalyticsQuery(
                startDate, endDate, ENGAGED_VIEWS_METRIC, null, null, null, null, null
        )));
        return merge(core, engagement, engagedViews);
    }

    public List<TrendPoint> getChannelTrend(LocalDate startDate, LocalDate endDate, String dimension) {
        Map<String, AnalyticsMetrics> merged = new LinkedHashMap<>();
        mergeDimensionTable(merged, query(new AnalyticsQuery(
                startDate, endDate, CORE_VIEW_METRICS, dimension, null, dimension, null, null
        )), dimension);
        mergeDimensionTable(merged, query(new AnalyticsQuery(
                startDate, endDate, ENGAGEMENT_METRICS, dimension, null, dimension, null, null
        )), dimension);
        mergeDimensionTable(merged, queryOptional(new AnalyticsQuery(
                startDate, endDate, ENGAGED_VIEWS_METRIC, dimension, null, dimension, null, null
        )), dimension);

        return merged.entrySet().stream()
                .map(entry -> new TrendPoint(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Recupera le metriche per tutti i video richiesti in batch.
     * Il filtro esplicito sui video consente di usare il video anche come dimensione
     * mantenendo le metriche del report di attività di base.
     */
    public Map<String, AnalyticsMetrics> getAllVideoPerformance(List<String> videoIds,
                                                                LocalDate startDate,
                                                                LocalDate endDate) {
        Map<String, AnalyticsMetrics> result = new LinkedHashMap<>();
        if (videoIds == null || videoIds.isEmpty()) {
            return result;
        }

        for (int from = 0; from < videoIds.size(); from += VIDEO_BATCH_SIZE) {
            List<String> batch = videoIds.subList(from, Math.min(from + VIDEO_BATCH_SIZE, videoIds.size()));
            String filter = "video==" + String.join(",", batch);

            mergeDimensionTable(result, query(new AnalyticsQuery(
                    startDate,
                    endDate,
                    CORE_VIEW_METRICS,
                    "video",
                    filter,
                    null,
                    batch.size(),
                    null
            )), "video");

            mergeDimensionTable(result, query(new AnalyticsQuery(
                    startDate,
                    endDate,
                    ENGAGEMENT_METRICS,
                    "video",
                    filter,
                    null,
                    batch.size(),
                    null
            )), "video");

            mergeDimensionTable(result, queryOptional(new AnalyticsQuery(
                    startDate,
                    endDate,
                    ENGAGED_VIEWS_METRIC,
                    "video",
                    filter,
                    null,
                    batch.size(),
                    null
            )), "video");
        }
        return result;
    }

    public AnalyticsMetrics getVideoAggregate(String videoId, LocalDate startDate, LocalDate endDate) {
        String filter = "video==" + videoId;
        AnalyticsMetrics core = firstMetrics(query(new AnalyticsQuery(
                startDate, endDate, CORE_VIEW_METRICS, null, filter, null, null, null
        )));
        AnalyticsMetrics engagement = firstMetrics(query(new AnalyticsQuery(
                startDate, endDate, ENGAGEMENT_METRICS, null, filter, null, null, null
        )));
        AnalyticsMetrics engagedViews = firstMetrics(queryOptional(new AnalyticsQuery(
                startDate, endDate, ENGAGED_VIEWS_METRIC, null, filter, null, null, null
        )));
        return merge(core, engagement, engagedViews);
    }

    public List<TrendPoint> getVideoTrend(String videoId,
                                          LocalDate startDate,
                                          LocalDate endDate,
                                          String dimension) {
        String filter = "video==" + videoId;
        Map<String, AnalyticsMetrics> merged = new LinkedHashMap<>();

        mergeDimensionTable(merged, query(new AnalyticsQuery(
                startDate, endDate, CORE_VIEW_METRICS, dimension, filter, dimension, null, null
        )), dimension);
        mergeDimensionTable(merged, query(new AnalyticsQuery(
                startDate, endDate, ENGAGEMENT_METRICS, dimension, filter, dimension, null, null
        )), dimension);
        mergeDimensionTable(merged, queryOptional(new AnalyticsQuery(
                startDate, endDate, ENGAGED_VIEWS_METRIC, dimension, filter, dimension, null, null
        )), dimension);

        return merged.entrySet().stream()
                .map(entry -> new TrendPoint(entry.getKey(), entry.getValue()))
                .toList();
    }

    public List<BreakdownMetric> getTrafficSources(String videoId, LocalDate startDate, LocalDate endDate) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "engagedViews,views,estimatedMinutesWatched",
                "insightTrafficSourceType",
                "video==" + videoId,
                "-views",
                null,
                null
        ));
        return table.rows().stream()
                .map(row -> new BreakdownMetric(
                        row.stringValue("insightTrafficSourceType"),
                        row.longValue("engagedViews"),
                        row.longValue("views"),
                        row.doubleValue("estimatedMinutesWatched"),
                        0,
                        0,
                        0
                ))
                .toList();
    }

    public List<BreakdownMetric> getCountries(String videoId,
                                              LocalDate startDate,
                                              LocalDate endDate,
                                              int limit) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,subscribersGained,subscribersLost",
                "country",
                "video==" + videoId,
                "-views",
                limit,
                null
        ));
        return table.rows().stream()
                .map(row -> new BreakdownMetric(
                        row.stringValue("country"),
                        0,
                        row.longValue("views"),
                        row.doubleValue("estimatedMinutesWatched"),
                        row.doubleValue("averageViewDuration"),
                        row.doubleValue("averageViewPercentage"),
                        row.longValue("subscribersGained") - row.longValue("subscribersLost")
                ))
                .toList();
    }

    public long getShortsFeedViews(String videoId, LocalDate startDate, LocalDate endDate) {
        return getTrafficSources(videoId, startDate, endDate).stream()
                .filter(metric -> "SHORTS".equalsIgnoreCase(metric.dimensionValue()))
                .mapToLong(BreakdownMetric::views)
                .sum();
    }

    private AnalyticsTable queryOptional(AnalyticsQuery analyticsQuery) {
        try {
            return query(analyticsQuery);
        } catch (GoogleApiException exception) {
            if (exception.getStatusCode() == 400
                    && exception.getResponseBody() != null
                    && exception.getResponseBody().contains("The query is not supported")) {
                log.warn("YouTube Analytics non supporta la query opzionale metrics={} dimensions={}; il valore sarà 0",
                        analyticsQuery.metrics(), analyticsQuery.dimensions());
                return new AnalyticsTable(List.of(), List.of());
            }
            throw exception;
        }
    }

    private AnalyticsTable query(AnalyticsQuery analyticsQuery) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(REPORTS_URL)
                .queryParam("ids", "channel==MINE")
                .queryParam("startDate", analyticsQuery.startDate())
                .queryParam("endDate", analyticsQuery.endDate())
                .queryParam("metrics", analyticsQuery.metrics());

        if (StringUtils.hasText(analyticsQuery.dimensions())) {
            builder.queryParam("dimensions", analyticsQuery.dimensions());
        }
        if (StringUtils.hasText(analyticsQuery.filters())) {
            builder.queryParam("filters", analyticsQuery.filters());
        }
        if (StringUtils.hasText(analyticsQuery.sort())) {
            builder.queryParam("sort", analyticsQuery.sort());
        }
        if (analyticsQuery.maxResults() != null) {
            builder.queryParam("maxResults", analyticsQuery.maxResults());
        }
        if (analyticsQuery.startIndex() != null) {
            builder.queryParam("startIndex", analyticsQuery.startIndex());
        }

        URI uri = builder.build().encode().toUri();
        log.debug("YouTube Analytics query: {}", uri);

        String accessToken = accessTokenService.getValidAccessToken();
        JsonNode root = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new GoogleApiException(response.getStatusCode().value(), "YouTube Analytics reports.query", body);
                })
                .body(JsonNode.class);
        return toTable(root);
    }

    private AnalyticsMetrics firstMetrics(AnalyticsTable table) {
        return table.rows().isEmpty() ? AnalyticsMetrics.empty() : toMetrics(table.rows().getFirst());
    }

    private void mergeDimensionTable(Map<String, AnalyticsMetrics> target,
                                     AnalyticsTable table,
                                     String dimension) {
        for (AnalyticsRow row : table.rows()) {
            String key = row.stringValue(dimension);
            if (key != null) {
                target.merge(key, toMetrics(row), (left, right) -> merge(left, right));
            }
        }
    }

    private AnalyticsMetrics merge(AnalyticsMetrics first, AnalyticsMetrics second) {
        return merge(new AnalyticsMetrics[]{first, second});
    }

    private AnalyticsMetrics merge(AnalyticsMetrics... values) {
        long engagedViews = 0;
        long views = 0;
        long comments = 0;
        long likes = 0;
        long shares = 0;
        double estimatedMinutesWatched = 0;
        double averageViewDuration = 0;
        double averageViewPercentage = 0;
        long subscribersGained = 0;
        long subscribersLost = 0;

        for (AnalyticsMetrics value : values) {
            if (value == null) {
                continue;
            }
            engagedViews += value.engagedViews();
            views += value.views();
            comments += value.comments();
            likes += value.likes();
            shares += value.shares();
            estimatedMinutesWatched += value.estimatedMinutesWatched();
            averageViewDuration += value.averageViewDurationSeconds();
            averageViewPercentage += value.averageViewPercentage();
            subscribersGained += value.subscribersGained();
            subscribersLost += value.subscribersLost();
        }

        return new AnalyticsMetrics(
                engagedViews,
                views,
                comments,
                likes,
                shares,
                estimatedMinutesWatched,
                averageViewDuration,
                averageViewPercentage,
                subscribersGained,
                subscribersLost
        );
    }

    private AnalyticsTable toTable(JsonNode root) {
        if (root == null) {
            return new AnalyticsTable(List.of(), List.of());
        }
        List<String> headers = new ArrayList<>();
        for (JsonNode header : root.path("columnHeaders")) {
            headers.add(header.path("name").asText());
        }

        List<AnalyticsRow> rows = new ArrayList<>();
        for (JsonNode rowNode : root.path("rows")) {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                values.put(headers.get(i), i < rowNode.size() ? rowNode.get(i) : null);
            }
            rows.add(new AnalyticsRow(values));
        }
        return new AnalyticsTable(headers, rows);
    }

    private AnalyticsMetrics toMetrics(AnalyticsRow row) {
        return new AnalyticsMetrics(
                row.longValue("engagedViews"),
                row.longValue("views"),
                row.longValue("comments"),
                row.longValue("likes"),
                row.longValue("shares"),
                row.doubleValue("estimatedMinutesWatched"),
                row.doubleValue("averageViewDuration"),
                row.doubleValue("averageViewPercentage"),
                row.longValue("subscribersGained"),
                row.longValue("subscribersLost")
        );
    }

    private record AnalyticsQuery(
            LocalDate startDate,
            LocalDate endDate,
            String metrics,
            String dimensions,
            String filters,
            String sort,
            Integer maxResults,
            Integer startIndex
    ) {
    }

    private record AnalyticsTable(List<String> headers, List<AnalyticsRow> rows) {
    }

    private record AnalyticsRow(Map<String, JsonNode> values) {

        String stringValue(String name) {
            JsonNode value = values.get(name);
            return value == null || value.isNull() ? null : value.asText();
        }

        long longValue(String name) {
            JsonNode value = values.get(name);
            return value == null || value.isNull() ? 0 : Math.round(value.asDouble());
        }

        double doubleValue(String name) {
            JsonNode value = values.get(name);
            return value == null || value.isNull() ? 0 : value.asDouble();
        }
    }

    public record AnalyticsMetrics(
            long engagedViews,
            long views,
            long comments,
            long likes,
            long shares,
            double estimatedMinutesWatched,
            double averageViewDurationSeconds,
            double averageViewPercentage,
            long subscribersGained,
            long subscribersLost
    ) {
        public static AnalyticsMetrics empty() {
            return new AnalyticsMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public long netSubscribers() {
            return subscribersGained - subscribersLost;
        }

        public double engagementRatePercentage() {
            return views == 0 ? 0 : ((double) likes + comments + shares) * 100 / views;
        }
    }

    public record TrendPoint(String period, AnalyticsMetrics metrics) {
    }

    public record BreakdownMetric(
            String dimensionValue,
            long engagedViews,
            long views,
            double estimatedMinutesWatched,
            double averageViewDurationSeconds,
            double averageViewPercentage,
            long netSubscribers
    ) {
    }
}
