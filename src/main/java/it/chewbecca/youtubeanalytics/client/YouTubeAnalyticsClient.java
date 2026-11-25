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
    // Il totale stimato è la metrica monetaria indispensabile. Le componenti vengono
    // richieste separatamente: un'eventuale incompatibilità di una metrica secondaria
    // non deve impedire di acquisire il ricavo totale del video.
    private static final String TOTAL_REVENUE_METRICS = String.join(",",
            "views",
            "estimatedRevenue"
    );
    private static final String REVENUE_COMPONENT_METRICS = String.join(",",
            "estimatedAdRevenue",
            "estimatedRedPartnerRevenue"
    );
    private static final String AD_PERFORMANCE_METRICS = String.join(",",
            "grossRevenue",
            "monetizedPlaybacks",
            "adImpressions",
            "playbackBasedCpm",
            "cpm"
    );
    private static final int VIDEO_BATCH_SIZE = 100;
    private static final int VIDEO_REVENUE_FILTER_BATCH_SIZE = 500;
    private static final int TOP_REVENUE_RESULTS_LIMIT = 200;

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

    /** Views nei primi sette giorni di calendario dalla pubblicazione: finestra uguale per ogni video. */
    public long getVideoFirstWeekViews(String videoId, LocalDate publicationDay) {
        return firstMetrics(query(new AnalyticsQuery(
                publicationDay, publicationDay.plusDays(6), "views", null,
                "video==" + videoId, null, null, null
        ))).views();
    }

    public AnalyticsMetrics getShortFirstWeekPerformance(String videoId, LocalDate publicationDay) {
        LocalDate lastDay = publicationDay.plusDays(6);
        String metrics = "views,engagedViews,averageViewPercentage,likes,comments,shares,subscribersGained";
        AnalyticsTable report = queryOptional(new AnalyticsQuery(
                publicationDay, lastDay, metrics, null, "video==" + videoId, null, null, null
        ));
        // Reuse the existing split queries if Google does not return the combined report.
        if (report.headers().isEmpty()) {
            return getVideoAggregate(videoId, publicationDay, lastDay);
        }
        return firstMetrics(report);
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

    public RevenueMetrics getChannelRevenue(LocalDate startDate, LocalDate endDate, String currency) {
        RevenueMetrics total = firstRevenueMetrics(query(new AnalyticsQuery(
                startDate, endDate, TOTAL_REVENUE_METRICS, null, null, null, null, null
        ), currency));
        RevenueMetrics components = firstRevenueMetrics(queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, REVENUE_COMPONENT_METRICS, null, null, null, null, null
        ), currency));
        RevenueMetrics ads = firstRevenueMetrics(queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, AD_PERFORMANCE_METRICS, null, null, null, null, null
        ), currency));
        return mergeRevenue(mergeRevenue(total, components), ads);
    }

    public Map<String, RevenueMetrics> getAllVideoRevenue(List<String> videoIds,
                                                          LocalDate startDate,
                                                          LocalDate endDate,
                                                          String currency) {
        if (videoIds == null || videoIds.isEmpty()) return new LinkedHashMap<>();
        Map<String, RevenueMetrics> result = pagedVideoRevenue(
                videoIds, startDate, endDate, currency, TOTAL_REVENUE_METRICS, false);
        Map<String, RevenueMetrics> optionalComponents = pagedVideoRevenue(
                videoIds, startDate, endDate, currency, REVENUE_COMPONENT_METRICS, true);
        optionalComponents.forEach((videoId, metrics) -> result.merge(videoId, metrics, this::mergeRevenue));
        Map<String, RevenueMetrics> optionalAds = pagedVideoRevenue(
                videoIds, startDate, endDate, currency, AD_PERFORMANCE_METRICS, true);
        optionalAds.forEach((videoId, metrics) -> result.merge(videoId, metrics, this::mergeRevenue));
        for (String videoId : videoIds) result.putIfAbsent(videoId, RevenueMetrics.empty());
        return result;
    }

    private Map<String, RevenueMetrics> pagedVideoRevenue(List<String> videoIds,
                                                           LocalDate startDate,
                                                           LocalDate endDate,
                                                           String currency,
                                                           String metrics,
                                                           boolean optional) {
        Map<String, RevenueMetrics> result = new LinkedHashMap<>();
        for (int from = 0; from < videoIds.size(); from += VIDEO_REVENUE_FILTER_BATCH_SIZE) {
            List<String> batch = videoIds.subList(
                    from, Math.min(from + VIDEO_REVENUE_FILTER_BATCH_SIZE, videoIds.size()));
            String filter = "video==" + String.join(",", batch);
            int startIndex = 1;
            while (true) {
                AnalyticsQuery request = new AnalyticsQuery(startDate, endDate, metrics, "video", filter,
                        "video", TOP_REVENUE_RESULTS_LIMIT, startIndex);
                AnalyticsTable page = optional ? queryOptionalRevenue(request, currency) : query(request, currency);
                for (AnalyticsRow row : page.rows()) {
                    String videoId = row.stringValue("video");
                    if (videoId != null) {
                        result.merge(videoId, toRevenueMetrics(row), this::mergeRevenue);
                    }
                }
                if (page.rows().size() < TOP_REVENUE_RESULTS_LIMIT) break;
                startIndex += TOP_REVENUE_RESULTS_LIMIT;
            }
        }
        return result;
    }

    public RevenueMetrics getVideoRevenue(String videoId,
                                          LocalDate startDate,
                                          LocalDate endDate,
                                          String currency) {
        String filter = "video==" + videoId;
        RevenueMetrics total = firstRevenueMetrics(query(new AnalyticsQuery(
                startDate, endDate, TOTAL_REVENUE_METRICS, null, filter, null, null, null
        ), currency));
        RevenueMetrics components = firstRevenueMetrics(queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, REVENUE_COMPONENT_METRICS, null, filter, null, null, null
        ), currency));
        RevenueMetrics ads = firstRevenueMetrics(queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, AD_PERFORMANCE_METRICS, null, filter, null, null, null
        ), currency));
        return mergeRevenue(mergeRevenue(total, components), ads);
    }

    public List<RevenueTrendPoint> getRevenueTrend(String videoId,
                                                   LocalDate startDate,
                                                   LocalDate endDate,
                                                   String dimension,
                                                   String currency) {
        String filter = StringUtils.hasText(videoId) ? "video==" + videoId : null;
        Map<String, RevenueMetrics> merged = new LinkedHashMap<>();
        mergeRevenueDimensionTable(merged, query(new AnalyticsQuery(
                startDate, endDate, TOTAL_REVENUE_METRICS, dimension, filter, dimension, null, null
        ), currency), dimension);
        mergeRevenueDimensionTable(merged, queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, REVENUE_COMPONENT_METRICS, dimension, filter, dimension, null, null
        ), currency), dimension);
        mergeRevenueDimensionTable(merged, queryOptionalRevenue(new AnalyticsQuery(
                startDate, endDate, AD_PERFORMANCE_METRICS, dimension, filter, dimension, null, null
        ), currency), dimension);
        return merged.entrySet().stream()
                .map(entry -> new RevenueTrendPoint(entry.getKey(), entry.getValue()))
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

    public List<BreakdownMetric> getSubscriptionStatus(String videoId,
                                                       LocalDate startDate,
                                                       LocalDate endDate) {
        String filter = StringUtils.hasText(videoId) ? "video==" + videoId : null;
        return getStandardBreakdown("subscribedStatus", filter, startDate, endDate, null);
    }

    public List<BreakdownMetric> getDevices(String videoId,
                                            LocalDate startDate,
                                            LocalDate endDate) {
        String filter = StringUtils.hasText(videoId) ? "video==" + videoId : null;
        return getStandardBreakdown("deviceType", filter, startDate, endDate, null);
    }

    public List<BreakdownMetric> getOperatingSystems(String videoId,
                                                     LocalDate startDate,
                                                     LocalDate endDate,
                                                     int limit) {
        String filter = StringUtils.hasText(videoId) ? "video==" + videoId : null;
        return getStandardBreakdown("operatingSystem", filter, startDate, endDate, limit);
    }

    public List<AudienceMetric> getDemographics(LocalDate startDate, LocalDate endDate) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "viewerPercentage",
                "ageGroup,gender",
                null,
                "-viewerPercentage",
                null,
                null
        ));
        return table.rows().stream()
                .map(row -> new AudienceMetric(
                        row.stringValue("ageGroup"),
                        row.stringValue("gender"),
                        row.doubleValue("viewerPercentage")
                ))
                .toList();
    }

    public List<RetentionMetric> getRetention(String videoId,
                                              LocalDate startDate,
                                              LocalDate endDate) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "audienceWatchRatio,relativeRetentionPerformance",
                "elapsedVideoTimeRatio",
                "video==" + videoId,
                "elapsedVideoTimeRatio",
                null,
                null
        ));
        return table.rows().stream()
                .map(row -> new RetentionMetric(
                        row.doubleValue("elapsedVideoTimeRatio"),
                        row.doubleValue("audienceWatchRatio"),
                        row.doubleValue("relativeRetentionPerformance")
                ))
                .toList();
    }

    public List<TrafficSourceDetailMetric> getTrafficSourceDetails(String videoId,
                                                                   String sourceType,
                                                                   LocalDate startDate,
                                                                   LocalDate endDate,
                                                                   int limit) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "views,estimatedMinutesWatched",
                "insightTrafficSourceDetail",
                "video==" + videoId + ";insightTrafficSourceType==" + sourceType,
                "-views",
                limit,
                null
        ));
        return table.rows().stream()
                .map(row -> new TrafficSourceDetailMetric(
                        row.stringValue("insightTrafficSourceDetail"),
                        row.longValue("views"),
                        row.doubleValue("estimatedMinutesWatched")
                ))
                .toList();
    }

    private List<BreakdownMetric> getStandardBreakdown(String dimension,
                                                       String filter,
                                                       LocalDate startDate,
                                                       LocalDate endDate,
                                                       Integer limit) {
        AnalyticsTable table = query(new AnalyticsQuery(
                startDate,
                endDate,
                "views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage",
                dimension,
                filter,
                "-views",
                limit,
                null
        ));
        return table.rows().stream()
                .map(row -> new BreakdownMetric(
                        row.stringValue(dimension),
                        0,
                        row.longValue("views"),
                        row.doubleValue("estimatedMinutesWatched"),
                        row.doubleValue("averageViewDuration"),
                        row.doubleValue("averageViewPercentage"),
                        0
                ))
                .toList();
    }

    public long getShortsFeedViews(String videoId, LocalDate startDate, LocalDate endDate) {
        return getTrafficSources(videoId, startDate, endDate).stream()
                .filter(metric -> "SHORTS".equalsIgnoreCase(metric.dimensionValue()))
                .mapToLong(BreakdownMetric::views)
                .sum();
    }

    /**
     * Targeted advisor queries expose only values returned by Google. An empty row
     * or an unsupported report stays distinct from a reported numeric zero.
     */
    public List<Map<String, Object>> advisorRows(String videoId, LocalDate from, LocalDate to, String report) {
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("ID video non valido");
        String filter = "video==" + videoId;
        var request = switch (report) {
            case "CORE" -> new AnalyticsQuery(from, to, CORE_VIEW_METRICS, null, filter, null, null, null);
            case "ENGAGEMENT" -> new AnalyticsQuery(from, to, ENGAGEMENT_METRICS, null, filter, null, null, null);
            case "ENGAGED_VIEWS" -> new AnalyticsQuery(from, to, ENGAGED_VIEWS_METRIC, null, filter, null, null, null);
            case "DAILY" -> new AnalyticsQuery(from, to, CORE_VIEW_METRICS, "day", filter, "day", null, null);
            case "MONETARY" -> new AnalyticsQuery(from, to, TOTAL_REVENUE_METRICS, null, filter, null, null, null);
            case "ADS" -> new AnalyticsQuery(from, to, AD_PERFORMANCE_METRICS, null, filter, null, null, null);
            case "RETENTION" -> new AnalyticsQuery(from, to, "audienceWatchRatio,relativeRetentionPerformance",
                    "elapsedVideoTimeRatio", filter, "elapsedVideoTimeRatio", null, null);
            default -> throw new IllegalArgumentException("Report performance sconosciuto");
        };
        return query(request).rows().stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            row.values().forEach((key, value) -> {
                if (value != null && value.isNumber()) result.put(key, value.numberValue());
                else if (value != null && value.isTextual()) result.put(key, value.asText());
            });
            return result;
        }).toList();
    }

    public Map<String, Long> advisorViewTotals(List<String> videoIds, LocalDate from, LocalDate to) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int offset = 0; offset < videoIds.size(); offset += VIDEO_BATCH_SIZE) {
            List<String> ids = videoIds.subList(offset, Math.min(offset + VIDEO_BATCH_SIZE, videoIds.size()));
            var table = query(new AnalyticsQuery(from, to, "views", "video",
                    "video==" + String.join(",", ids), null, ids.size(), null));
            for (var row : table.rows()) {
                var video = row.stringValue("video");
                var views = row.values().get("views");
                if (video != null && views != null && views.isNumber()) result.put(video, views.longValue());
            }
        }
        return result;
    }

    private AnalyticsTable queryOptionalRevenue(AnalyticsQuery analyticsQuery, String currency) {
        try {
            return query(analyticsQuery, currency);
        } catch (GoogleApiException exception) {
            if (exception.getStatusCode() == 400) {
                log.warn("Metriche pubblicitarie opzionali non supportate per metrics={} dimensions={}; " +
                                "i ricavi stimati principali restano disponibili. Risposta={}",
                        analyticsQuery.metrics(), analyticsQuery.dimensions(), exception.getResponseBody());
                return new AnalyticsTable(List.of(), List.of());
            }
            throw exception;
        }
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
        return query(analyticsQuery, null);
    }

    private AnalyticsTable query(AnalyticsQuery analyticsQuery, String currency) {
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
        if (StringUtils.hasText(currency)) {
            builder.queryParam("currency", currency);
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

    private RevenueMetrics firstRevenueMetrics(AnalyticsTable table) {
        return table.rows().isEmpty() ? RevenueMetrics.empty() : toRevenueMetrics(table.rows().getFirst());
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

    private void mergeRevenueDimensionTable(Map<String, RevenueMetrics> target,
                                            AnalyticsTable table,
                                            String dimension) {
        for (AnalyticsRow row : table.rows()) {
            String key = row.stringValue(dimension);
            if (key != null) target.merge(key, toRevenueMetrics(row), this::mergeRevenue);
        }
    }

    private RevenueMetrics mergeRevenue(RevenueMetrics first, RevenueMetrics second) {
        if (first == null) return second == null ? RevenueMetrics.empty() : second;
        if (second == null) return first;
        return new RevenueMetrics(
                Math.max(first.views(), second.views()),
                first.estimatedRevenue() + second.estimatedRevenue(),
                first.estimatedAdRevenue() + second.estimatedAdRevenue(),
                first.estimatedPremiumRevenue() + second.estimatedPremiumRevenue(),
                first.grossRevenue() + second.grossRevenue(),
                first.monetizedPlaybacks() + second.monetizedPlaybacks(),
                first.adImpressions() + second.adImpressions(),
                second.playbackBasedCpm() != 0 ? second.playbackBasedCpm() : first.playbackBasedCpm(),
                second.adImpressionCpm() != 0 ? second.adImpressionCpm() : first.adImpressionCpm()
        );
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

    private RevenueMetrics toRevenueMetrics(AnalyticsRow row) {
        return new RevenueMetrics(
                row.longValue("views"),
                row.doubleValue("estimatedRevenue"),
                row.doubleValue("estimatedAdRevenue"),
                row.doubleValue("estimatedRedPartnerRevenue"),
                row.doubleValue("grossRevenue"),
                row.longValue("monetizedPlaybacks"),
                row.longValue("adImpressions"),
                row.doubleValue("playbackBasedCpm"),
                row.doubleValue("cpm")
        );
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

    public record AudienceMetric(String ageGroup, String gender, double viewerPercentage) {
    }

    public record RetentionMetric(
            double elapsedVideoTimeRatio,
            double audienceWatchRatio,
            double relativeRetentionPerformance
    ) {
    }

    public record TrafficSourceDetailMetric(
            String detail,
            long views,
            double estimatedMinutesWatched
    ) {
    }

    public record RevenueMetrics(
            long views,
            double estimatedRevenue,
            double estimatedAdRevenue,
            double estimatedPremiumRevenue,
            double grossRevenue,
            long monetizedPlaybacks,
            long adImpressions,
            double playbackBasedCpm,
            double adImpressionCpm
    ) {
        public static RevenueMetrics empty() {
            return new RevenueMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public double estimatedRpm() {
            return views == 0 ? 0 : estimatedRevenue * 1000.0 / views;
        }

        public double monetizedPlaybackRatePercentage() {
            return views == 0 ? 0 : monetizedPlaybacks * 100.0 / views;
        }
    }

    public record RevenueTrendPoint(String period, RevenueMetrics metrics) {
    }

}
