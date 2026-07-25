package it.chewbecca.youtubeanalytics.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.exception.ResourceNotFoundException;
import it.chewbecca.youtubeanalytics.meta.api.model.InstagramMediaType;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortBy;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortOrder;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphClient;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.meta.oauth.MetaAccessTokenService;
import it.chewbecca.youtubeanalytics.meta.oauth.MetaCredential;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InstagramAnalyticsService {

    private static final String ACCOUNT_FIELDS = "id,username,name,biography,website,profile_picture_url,followers_count,follows_count,media_count";
    private static final String MEDIA_FIELDS = "id,caption,media_type,media_product_type,media_url,thumbnail_url,permalink,timestamp,username,like_count,comments_count," +
            "children{id,media_type,media_url,thumbnail_url,permalink,timestamp}";

    private static final List<String> DEFAULT_ACCOUNT_METRICS = List.of(
            "views",
            "reach",
            "accounts_engaged",
            "total_interactions",
            "likes",
            "comments",
            "saves",
            "shares",
            "replies",
            "profile_links_taps",
            "follows_and_unfollows"
    );
    private static final List<String> DEFAULT_ACCOUNT_TREND_METRICS = List.of(
            "views",
            "reach",
            "accounts_engaged",
            "total_interactions"
    );
    private static final List<String> DEFAULT_MEDIA_METRICS = List.of(
            "views",
            "reach",
            "likes",
            "comments",
            "saved",
            "shares",
            "total_interactions"
    );
    private static final List<String> DEFAULT_REEL_METRICS = List.of(
            "views",
            "reach",
            "likes",
            "comments",
            "saved",
            "shares",
            "total_interactions",
            "ig_reels_avg_watch_time",
            "ig_reels_video_view_total_time"
    );
    private static final List<String> DEFAULT_STORY_METRICS = List.of(
            "views",
            "reach",
            "replies",
            "shares",
            "total_interactions",
            "navigation"
    );

    private final MetaGraphClient graphClient;
    private final MetaAccessTokenService accessTokenService;
    private final MetaDateRangeResolver dateRangeResolver;
    private final MetaInsightService insightService;
    private final MetaProperties properties;

    public InstagramAnalyticsService(MetaGraphClient graphClient,
                                     MetaAccessTokenService accessTokenService,
                                     MetaDateRangeResolver dateRangeResolver,
                                     MetaInsightService insightService,
                                     MetaProperties properties) {
        this.graphClient = graphClient;
        this.accessTokenService = accessTokenService;
        this.dateRangeResolver = dateRangeResolver;
        this.insightService = insightService;
        this.properties = properties;
    }

    @Cacheable("metaInstagramAccount")
    public MetaApiModels.InstagramAccount account() {
        MetaCredential credential = accessTokenService.requireCredential();
        JsonNode root = graphClient.get(
                "/" + credential.getInstagramAccountId(),
                accessTokenService.getPageAccessToken(),
                Map.of("fields", ACCOUNT_FIELDS),
                "dettaglio account Instagram"
        );
        return mapAccount(root);
    }

    public MetaApiModels.InstagramSummary summary(LocalDate startDate, LocalDate endDate) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        MetaInsightService.InsightLoadResult insights = insightService.loadInstagramSummary(
                credential.getInstagramAccountId(),
                accessTokenService.getPageAccessToken(),
                DEFAULT_ACCOUNT_METRICS,
                period
        );
        List<JsonNode> media = loadMedia(period);
        long reels = media.stream().filter(node -> detectType(node) == InstagramMediaType.REEL).count();
        return new MetaApiModels.InstagramSummary(
                account(),
                period,
                insightService.latestValues(insights.metrics()),
                insightService.periodTotals(insights.metrics()),
                insights.metrics(),
                media.size(),
                reels,
                insights.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.InsightsResponse insights(LocalDate startDate,
                                                   LocalDate endDate,
                                                   String metrics,
                                                   String aggregationPeriod,
                                                   String metricType,
                                                   String breakdown,
                                                   String timeframe) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        MetaInsightService.InsightLoadResult loaded = insightService.load(
                credential.getInstagramAccountId(),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, DEFAULT_ACCOUNT_METRICS),
                period,
                aggregationPeriod,
                metricType,
                breakdown,
                timeframe,
                "Instagram"
        );
        return new MetaApiModels.InsightsResponse(
                "INSTAGRAM",
                credential.getInstagramAccountId(),
                period,
                loaded.metrics(),
                loaded.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.TrendResponse trend(LocalDate startDate, LocalDate endDate, String metrics) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        List<String> selected = insightService.parseMetrics(metrics, DEFAULT_ACCOUNT_TREND_METRICS);
        MetaInsightService.InsightLoadResult loaded = insightService.load(
                credential.getInstagramAccountId(),
                accessTokenService.getPageAccessToken(),
                selected,
                period,
                "day",
                "time_series",
                null,
                null,
                "Instagram"
        );
        if (loaded.metrics().isEmpty()) {
            loaded = insightService.load(
                    credential.getInstagramAccountId(),
                    accessTokenService.getPageAccessToken(),
                    selected,
                    period,
                    "day",
                    null,
                    null,
                    null,
                    "Instagram"
            );
        }
        return new MetaApiModels.TrendResponse(
                "INSTAGRAM",
                credential.getInstagramAccountId(),
                period,
                insightService.toTrend(loaded.metrics()),
                loaded.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.InstagramMediaListResponse media(LocalDate startDate,
                                                          LocalDate endDate,
                                                          InstagramMediaType type,
                                                          String query,
                                                          int page,
                                                          int size,
                                                          MetaSortBy sortBy,
                                                          MetaSortOrder order,
                                                          boolean includeInsights) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        validatePage(page, size);
        List<String> warnings = new ArrayList<>();
        List<MetaApiModels.InstagramMediaItem> items = loadMedia(period).stream()
                .map(node -> mapMedia(node, includeInsights, warnings))
                .filter(item -> type == null || type == InstagramMediaType.ALL || item.contentType() == type)
                .filter(item -> matches(query, item.caption(), item.id()))
                .sorted(mediaComparator(sortBy, order))
                .toList();
        return pageMedia(period, type == null ? InstagramMediaType.ALL : type, query,
                page, size, sortBy, order, items, warnings);
    }

    public MetaApiModels.InstagramMediaListResponse stories(boolean includeInsights) {
        MetaCredential credential = accessTokenService.requireCredential();
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(null, null);
        List<String> warnings = new ArrayList<>();
        List<JsonNode> nodes = graphClient.getAll(
                "/" + credential.getInstagramAccountId() + "/stories",
                accessTokenService.getPageAccessToken(),
                Map.of("fields", MEDIA_FIELDS),
                Math.max(1, properties.maxContentItems()),
                "storie Instagram attive"
        );
        List<MetaApiModels.InstagramMediaItem> items = nodes.stream()
                .map(node -> mapMedia(node, includeInsights, warnings))
                .sorted(mediaComparator(MetaSortBy.PUBLISHED_AT, MetaSortOrder.DESC))
                .toList();
        return new MetaApiModels.InstagramMediaListResponse(
                period, InstagramMediaType.STORY, null, 0, items.size(), items.size(), items.isEmpty() ? 0 : 1,
                MetaSortBy.PUBLISHED_AT, MetaSortOrder.DESC, items, List.copyOf(warnings), Instant.now()
        );
    }

    public MetaApiModels.InstagramMediaItem media(String mediaId, boolean includeInsights) {
        List<String> warnings = new ArrayList<>();
        JsonNode root = graphClient.get(
                "/" + safeId(mediaId),
                accessTokenService.getPageAccessToken(),
                Map.of("fields", MEDIA_FIELDS),
                "dettaglio contenuto Instagram"
        );
        if (!root.hasNonNull("id")) {
            throw new ResourceNotFoundException("Contenuto Instagram non trovato: " + mediaId);
        }
        return mapMedia(root, includeInsights, warnings);
    }

    public MetaApiModels.InsightsResponse mediaInsights(String mediaId, String metrics) {
        MetaApiModels.InstagramMediaItem item = media(mediaId, false);
        List<String> defaults = metricsFor(item.contentType());
        MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                safeId(mediaId),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, defaults),
                "Instagram media"
        );
        return new MetaApiModels.InsightsResponse(
                "INSTAGRAM_MEDIA",
                mediaId,
                null,
                loaded.metrics(),
                loaded.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.InstagramMediaListResponse topContent(LocalDate startDate,
                                                               LocalDate endDate,
                                                               InstagramMediaType type,
                                                               int limit,
                                                               MetaSortBy sortBy) {
        int normalizedLimit = Math.max(1, Math.min(25, limit));
        return media(startDate, endDate, type, null, 0, normalizedLimit,
                sortBy == null ? MetaSortBy.REACH : sortBy, MetaSortOrder.DESC, true);
    }

    private List<JsonNode> loadMedia(MetaApiModels.ReportPeriod period) {
        MetaCredential credential = accessTokenService.requireCredential();
        List<JsonNode> all = graphClient.getAll(
                "/" + credential.getInstagramAccountId() + "/media",
                accessTokenService.getPageAccessToken(),
                Map.of("fields", MEDIA_FIELDS),
                Math.max(1, properties.maxContentItems()),
                "elenco contenuti Instagram"
        );
        return all.stream().filter(node -> inPeriod(node, period)).toList();
    }

    private MetaApiModels.InstagramAccount mapAccount(JsonNode node) {
        return new MetaApiModels.InstagramAccount(
                text(node, "id"),
                text(node, "username"),
                text(node, "name"),
                text(node, "biography"),
                text(node, "website"),
                text(node, "profile_picture_url"),
                longOrNull(node.path("followers_count")),
                longOrNull(node.path("follows_count")),
                longOrNull(node.path("media_count"))
        );
    }

    private MetaApiModels.InstagramMediaItem mapMedia(JsonNode node,
                                                       boolean includeInsights,
                                                       List<String> warnings) {
        InstagramMediaType type = detectType(node);
        List<MetaApiModels.InsightMetric> metrics = List.of();
        Map<String, JsonNode> insightValues = Map.of();
        if (includeInsights) {
            MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                    node.path("id").asText(),
                    accessTokenService.getPageAccessToken(),
                    metricsFor(type),
                    "Instagram media"
            );
            metrics = loaded.metrics();
            insightValues = insightService.latestValues(metrics);
            warnings.addAll(loaded.warnings().stream().map(warning -> node.path("id").asText() + " - " + warning).toList());
        }
        List<MetaApiModels.InstagramChildMedia> children = new ArrayList<>();
        node.path("children").path("data").forEach(child -> children.add(new MetaApiModels.InstagramChildMedia(
                text(child, "id"),
                text(child, "media_type"),
                text(child, "media_url"),
                text(child, "thumbnail_url"),
                text(child, "permalink"),
                text(child, "timestamp")
        )));
        return new MetaApiModels.InstagramMediaItem(
                text(node, "id"),
                text(node, "caption"),
                text(node, "media_type"),
                text(node, "media_product_type"),
                type,
                text(node, "media_url"),
                text(node, "thumbnail_url"),
                text(node, "permalink"),
                text(node, "timestamp"),
                text(node, "username"),
                longOrNull(node.path("like_count")),
                longOrNull(node.path("comments_count")),
                List.copyOf(children),
                insightValues,
                metrics
        );
    }

    private List<String> metricsFor(InstagramMediaType type) {
        return switch (type) {
            case REEL -> DEFAULT_REEL_METRICS;
            case STORY -> DEFAULT_STORY_METRICS;
            default -> DEFAULT_MEDIA_METRICS;
        };
    }

    private InstagramMediaType detectType(JsonNode node) {
        String productType = node.path("media_product_type").asText("").toUpperCase(Locale.ROOT);
        String mediaType = node.path("media_type").asText("").toUpperCase(Locale.ROOT);
        if (productType.contains("REEL")) return InstagramMediaType.REEL;
        if (productType.contains("STORY")) return InstagramMediaType.STORY;
        if (mediaType.contains("CAROUSEL")) return InstagramMediaType.CAROUSEL;
        if (mediaType.contains("IMAGE")) return InstagramMediaType.IMAGE;
        if (mediaType.contains("VIDEO")) return InstagramMediaType.VIDEO;
        return InstagramMediaType.ALL;
    }

    private Comparator<MetaApiModels.InstagramMediaItem> mediaComparator(MetaSortBy sortBy, MetaSortOrder order) {
        MetaSortBy normalized = sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy;
        Comparator<MetaApiModels.InstagramMediaItem> comparator = switch (normalized) {
            case VIEWS -> Comparator.comparingLong(item -> metric(item.insightValues(), "views"));
            case REACH -> Comparator.comparingLong(item -> metric(item.insightValues(), "reach"));
            case LIKES -> Comparator.comparingLong(item -> item.likeCount() == null
                    ? metric(item.insightValues(), "likes") : item.likeCount());
            case COMMENTS -> Comparator.comparingLong(item -> item.commentsCount() == null
                    ? metric(item.insightValues(), "comments") : item.commentsCount());
            case SHARES -> Comparator.comparingLong(item -> metric(item.insightValues(), "shares"));
            case SAVES -> Comparator.comparingLong(item -> metric(item.insightValues(), "saved"));
            case ENGAGEMENT -> Comparator.comparingLong(item -> {
                long total = metric(item.insightValues(), "total_interactions");
                if (total > 0) return total;
                return (item.likeCount() == null ? 0 : item.likeCount())
                        + (item.commentsCount() == null ? 0 : item.commentsCount())
                        + metric(item.insightValues(), "shares")
                        + metric(item.insightValues(), "saved");
            });
            case PUBLISHED_AT -> Comparator.comparing(item -> instant(item.timestamp()), Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        return order == MetaSortOrder.ASC ? comparator : comparator.reversed();
    }

    private MetaApiModels.InstagramMediaListResponse pageMedia(MetaApiModels.ReportPeriod period,
                                                                InstagramMediaType type,
                                                                String query,
                                                                int page,
                                                                int size,
                                                                MetaSortBy sortBy,
                                                                MetaSortOrder order,
                                                                List<MetaApiModels.InstagramMediaItem> items,
                                                                List<String> warnings) {
        int from = Math.min(page * size, items.size());
        int to = Math.min(from + size, items.size());
        int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / size);
        return new MetaApiModels.InstagramMediaListResponse(
                period, type, query, page, size, items.size(), totalPages,
                sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy,
                order == null ? MetaSortOrder.DESC : order,
                List.copyOf(items.subList(from, to)), List.copyOf(warnings), Instant.now()
        );
    }

    private boolean inPeriod(JsonNode node, MetaApiModels.ReportPeriod period) {
        String timestamp = text(node, "timestamp");
        if (!StringUtils.hasText(timestamp)) return true;
        Instant parsed = instant(timestamp);
        if (parsed == null) {
            return true;
        }
        LocalDate date = parsed.atZone(ZoneOffset.UTC).toLocalDate();
        return !date.isBefore(period.startDate()) && !date.isAfter(period.endDate());
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page deve essere >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size deve essere compreso tra 1 e 100");
    }

    private boolean matches(String query, String... values) {
        if (!StringUtils.hasText(query)) return true;
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(normalized)) return true;
        }
        return false;
    }

    private String safeId(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z0-9_:-]+")) {
            throw new IllegalArgumentException("ID Instagram non valido");
        }
        return value;
    }

    private long metric(Map<String, JsonNode> values, String name) {
        JsonNode value = values.get(name);
        return value == null ? 0 : value.asLong(0);
    }

    private Instant instant(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private Long longOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asLong();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText()) ? null : value.asText();
    }
}
