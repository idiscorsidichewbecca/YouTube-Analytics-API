package it.chewbecca.youtubeanalytics.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.exception.ResourceNotFoundException;
import it.chewbecca.youtubeanalytics.meta.api.model.FacebookContentType;
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
public class FacebookAnalyticsService {

    private static final String PAGE_FIELDS = "id,name,username,link,about,category,followers_count,fan_count,verification_status,picture{url}";
    private static final String POST_FIELDS = "id,message,created_time,permalink_url,full_picture,status_type,shares," +
            "reactions.limit(0).summary(true),comments.limit(0).summary(true)," +
            "attachments{media_type,type,url,title,description,target,subattachments}";
    private static final List<String> DEFAULT_PAGE_METRICS = List.of(
            "page_impressions",
            "page_impressions_unique",
            "page_post_engagements",
            "page_daily_follows",
            "page_daily_follows_unique",
            "page_daily_unfollows_unique",
            "page_follows",
            "page_posts_impressions",
            "page_posts_impressions_unique"
    );
    private static final List<String> DEFAULT_PAGE_TREND_METRICS = List.of(
            "page_impressions",
            "page_impressions_unique",
            "page_post_engagements",
            "page_daily_follows_unique",
            "page_daily_unfollows_unique"
    );
    private static final List<String> DEFAULT_POST_METRICS = List.of(
            "post_impressions",
            "post_impressions_unique",
            "post_clicks",
            "post_engaged_users"
    );
    private static final List<String> DEFAULT_VIDEO_METRICS = List.of(
            "post_impressions",
            "post_impressions_unique",
            "post_video_views",
            "post_video_view_time",
            "post_video_social_actions_count_unique"
    );

    private final MetaGraphClient graphClient;
    private final MetaAccessTokenService accessTokenService;
    private final MetaDateRangeResolver dateRangeResolver;
    private final MetaInsightService insightService;
    private final MetaProperties properties;

    public FacebookAnalyticsService(MetaGraphClient graphClient,
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

    @Cacheable("metaFacebookPage")
    public MetaApiModels.FacebookPage page() {
        MetaCredential credential = accessTokenService.requireCredential();
        JsonNode root = graphClient.get(
                "/" + credential.getPageId(),
                accessTokenService.getPageAccessToken(),
                Map.of("fields", PAGE_FIELDS),
                "dettaglio Pagina Facebook"
        );
        return mapPage(root);
    }

    public MetaApiModels.FacebookSummary summary(LocalDate startDate, LocalDate endDate) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        String pageToken = accessTokenService.getPageAccessToken();
        MetaInsightService.InsightLoadResult insights = insightService.load(
                credential.getPageId(),
                pageToken,
                DEFAULT_PAGE_METRICS,
                period,
                "day",
                null,
                null,
                null,
                "Facebook"
        );
        List<JsonNode> posts = loadPosts(period, POST_FIELDS);
        List<JsonNode> videos = loadVideos(period);
        long reels = videos.stream().filter(this::isReelVideo).count();
        return new MetaApiModels.FacebookSummary(
                page(),
                period,
                insightService.latestValues(insights.metrics()),
                insightService.periodTotals(insights.metrics()),
                insights.metrics(),
                posts.size(),
                videos.size(),
                reels,
                insights.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.InsightsResponse insights(LocalDate startDate,
                                                   LocalDate endDate,
                                                   String metrics,
                                                   String aggregationPeriod) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        MetaInsightService.InsightLoadResult loaded = insightService.load(
                credential.getPageId(),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, DEFAULT_PAGE_METRICS),
                period,
                StringUtils.hasText(aggregationPeriod) ? aggregationPeriod : "day",
                null,
                null,
                null,
                "Facebook"
        );
        return new MetaApiModels.InsightsResponse(
                "FACEBOOK",
                credential.getPageId(),
                period,
                loaded.metrics(),
                loaded.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.TrendResponse trend(LocalDate startDate, LocalDate endDate, String metrics) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        MetaCredential credential = accessTokenService.requireCredential();
        MetaInsightService.InsightLoadResult loaded = insightService.load(
                credential.getPageId(),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, DEFAULT_PAGE_TREND_METRICS),
                period,
                "day",
                null,
                null,
                null,
                "Facebook"
        );
        return new MetaApiModels.TrendResponse(
                "FACEBOOK",
                credential.getPageId(),
                period,
                insightService.toTrend(loaded.metrics()),
                loaded.warnings(),
                Instant.now()
        );
    }

    public MetaApiModels.FacebookContentListResponse posts(LocalDate startDate,
                                                           LocalDate endDate,
                                                           FacebookContentType type,
                                                           String query,
                                                           int page,
                                                           int size,
                                                           MetaSortBy sortBy,
                                                           MetaSortOrder order,
                                                           boolean includeInsights) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        validatePage(page, size);
        List<String> warnings = new ArrayList<>();
        List<MetaApiModels.FacebookContentItem> items = loadPosts(period, POST_FIELDS).stream()
                .map(node -> mapPost(node, includeInsights, period, warnings))
                .filter(item -> type == null || type == FacebookContentType.ALL || item.contentType() == type)
                .filter(item -> matches(query, item.message(), item.id()))
                .sorted(postComparator(sortBy, order))
                .toList();
        return pagePosts(period, type == null ? FacebookContentType.ALL : type, query, page, size,
                sortBy, order, items, warnings);
    }

    public MetaApiModels.FacebookContentItem post(String postId, boolean includeInsights) {
        List<String> warnings = new ArrayList<>();
        JsonNode root = graphClient.get(
                "/" + safeId(postId),
                accessTokenService.getPageAccessToken(),
                Map.of("fields", POST_FIELDS),
                "dettaglio post Facebook"
        );
        if (!root.hasNonNull("id")) {
            throw new ResourceNotFoundException("Post Facebook non trovato: " + postId);
        }
        return mapPost(root, includeInsights, null, warnings);
    }

    public MetaApiModels.InsightsResponse postInsights(String postId, String metrics) {
        MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                safeId(postId),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, DEFAULT_POST_METRICS),
                "Facebook post"
        );
        return new MetaApiModels.InsightsResponse(
                "FACEBOOK_POST", postId, null, loaded.metrics(), loaded.warnings(), Instant.now()
        );
    }

    public MetaApiModels.FacebookVideoListResponse videos(LocalDate startDate,
                                                          LocalDate endDate,
                                                          boolean reelsOnly,
                                                          String query,
                                                          int page,
                                                          int size,
                                                          MetaSortBy sortBy,
                                                          MetaSortOrder order,
                                                          boolean includeInsights) {
        MetaApiModels.ReportPeriod period = dateRangeResolver.resolve(startDate, endDate);
        validatePage(page, size);
        List<String> warnings = new ArrayList<>();
        if (reelsOnly) {
            warnings.add("La classificazione dei Reel Facebook è ricavata dai post pubblicati, dal permalink e dagli allegati restituiti da Meta.");
        }
        List<MetaApiModels.FacebookVideoItem> items = loadVideos(period).stream()
                .map(node -> mapVideo(node, includeInsights, period, warnings))
                .filter(item -> !reelsOnly || item.reel())
                .filter(item -> matches(query, item.title(), item.description(), item.id()))
                .sorted(videoComparator(sortBy, order))
                .toList();
        return pageVideos(period, reelsOnly, query, page, size, sortBy, order, items, warnings);
    }

    public MetaApiModels.FacebookVideoItem video(String videoId, boolean includeInsights) {
        List<String> warnings = new ArrayList<>();
        JsonNode root = graphClient.get(
                "/" + safeId(videoId),
                accessTokenService.getPageAccessToken(),
                Map.of("fields", POST_FIELDS),
                "dettaglio contenuto video Facebook"
        );
        if (!root.hasNonNull("id")) {
            throw new ResourceNotFoundException("Video Facebook non trovato: " + videoId);
        }
        return mapVideo(root, includeInsights, null, warnings);
    }

    public MetaApiModels.InsightsResponse videoInsights(String videoId, String metrics) {
        MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                safeId(videoId),
                accessTokenService.getPageAccessToken(),
                insightService.parseMetrics(metrics, DEFAULT_VIDEO_METRICS),
                "Facebook video"
        );
        return new MetaApiModels.InsightsResponse(
                "FACEBOOK_VIDEO", videoId, null, loaded.metrics(), loaded.warnings(), Instant.now()
        );
    }

    public MetaApiModels.FacebookContentListResponse topContent(LocalDate startDate,
                                                                LocalDate endDate,
                                                                FacebookContentType type,
                                                                int limit,
                                                                MetaSortBy sortBy) {
        int normalizedLimit = Math.max(1, Math.min(25, limit));
        return posts(startDate, endDate, type, null, 0, normalizedLimit,
                sortBy == null ? MetaSortBy.REACH : sortBy, MetaSortOrder.DESC, true);
    }

    private List<JsonNode> loadPosts(MetaApiModels.ReportPeriod period, String fields) {
        MetaCredential credential = accessTokenService.requireCredential();
        return graphClient.getAll(
                "/" + credential.getPageId() + "/published_posts",
                accessTokenService.getPageAccessToken(),
                Map.of(
                        "fields", fields,
                        "since", period.startDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        "until", period.endDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                ),
                Math.max(1, properties.maxContentItems()),
                "elenco post pubblicati Facebook"
        );
    }

    private List<JsonNode> loadVideos(MetaApiModels.ReportPeriod period) {
        return loadPosts(period, POST_FIELDS).stream()
                .filter(node -> {
                    FacebookContentType type = detectPostType(node);
                    return type == FacebookContentType.VIDEO || type == FacebookContentType.REEL;
                })
                .toList();
    }

    private MetaApiModels.FacebookPage mapPage(JsonNode node) {
        return new MetaApiModels.FacebookPage(
                text(node, "id"),
                text(node, "name"),
                text(node, "username"),
                text(node, "link"),
                text(node, "about"),
                text(node, "category"),
                longOrNull(node.path("followers_count")),
                longOrNull(node.path("fan_count")),
                text(node, "verification_status"),
                text(node.path("picture").path("data"), "url")
        );
    }

    private MetaApiModels.FacebookContentItem mapPost(JsonNode node,
                                                       boolean includeInsights,
                                                       MetaApiModels.ReportPeriod period,
                                                       List<String> warnings) {
        FacebookContentType contentType = detectPostType(node);
        List<MetaApiModels.InsightMetric> metrics = List.of();
        Map<String, JsonNode> insightValues = Map.of();
        if (includeInsights) {
            MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                    node.path("id").asText(),
                    accessTokenService.getPageAccessToken(),
                    DEFAULT_POST_METRICS,
                    "Facebook post"
            );
            metrics = loaded.metrics();
            insightValues = insightService.latestValues(metrics);
            warnings.addAll(loaded.warnings().stream().map(warning -> node.path("id").asText() + " - " + warning).toList());
        }
        return new MetaApiModels.FacebookContentItem(
                text(node, "id"),
                text(node, "message"),
                text(node, "created_time"),
                text(node, "permalink_url"),
                text(node, "full_picture"),
                text(node, "status_type"),
                contentType,
                node.path("reactions").path("summary").path("total_count").asLong(0),
                node.path("comments").path("summary").path("total_count").asLong(0),
                node.path("shares").path("count").asLong(0),
                node.path("attachments").path("data"),
                insightValues,
                metrics
        );
    }

    private MetaApiModels.FacebookVideoItem mapVideo(JsonNode node,
                                                      boolean includeInsights,
                                                      MetaApiModels.ReportPeriod period,
                                                      List<String> warnings) {
        List<MetaApiModels.InsightMetric> metrics = List.of();
        Map<String, JsonNode> insightValues = Map.of();
        if (includeInsights) {
            MetaInsightService.InsightLoadResult loaded = insightService.loadLifetime(
                    node.path("id").asText(),
                    accessTokenService.getPageAccessToken(),
                    DEFAULT_VIDEO_METRICS,
                    "Facebook video"
            );
            metrics = loaded.metrics();
            insightValues = insightService.latestValues(metrics);
            warnings.addAll(loaded.warnings().stream().map(warning -> node.path("id").asText() + " - " + warning).toList());
        }
        JsonNode attachment = node.path("attachments").path("data").path(0);
        String title = text(attachment, "title");
        if (!StringUtils.hasText(title)) {
            title = text(node, "message");
        }
        return new MetaApiModels.FacebookVideoItem(
                text(node, "id"),
                title,
                StringUtils.hasText(text(attachment, "description"))
                        ? text(attachment, "description")
                        : text(node, "message"),
                text(node, "created_time"),
                null,
                text(node, "permalink_url"),
                text(node, "full_picture"),
                null,
                text(node, "status_type"),
                isReelVideo(node),
                insightValues,
                metrics
        );
    }

    private FacebookContentType detectPostType(JsonNode node) {
        String permalink = text(node, "permalink_url");
        if (permalink != null && permalink.toLowerCase(Locale.ROOT).contains("/reel/")) {
            return FacebookContentType.REEL;
        }
        JsonNode attachment = node.path("attachments").path("data").path(0);
        String mediaType = attachment.path("media_type").asText("").toLowerCase(Locale.ROOT);
        String type = attachment.path("type").asText("").toLowerCase(Locale.ROOT);
        String combined = mediaType + " " + type + " " + node.path("status_type").asText("");
        if (combined.contains("video")) return FacebookContentType.VIDEO;
        if (combined.contains("photo") || combined.contains("image")) return FacebookContentType.PHOTO;
        if (combined.contains("link")) return FacebookContentType.LINK;
        return FacebookContentType.POST;
    }

    private boolean isReelVideo(JsonNode node) {
        String permalink = text(node, "permalink_url");
        if (permalink != null && permalink.toLowerCase(Locale.ROOT).contains("/reel/")) {
            return true;
        }
        return node.path("format").toString().toLowerCase(Locale.ROOT).contains("reel");
    }

    private Comparator<MetaApiModels.FacebookContentItem> postComparator(MetaSortBy sortBy, MetaSortOrder order) {
        MetaSortBy normalized = sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy;
        Comparator<MetaApiModels.FacebookContentItem> comparator = switch (normalized) {
            case VIEWS -> Comparator.comparingLong(item -> metric(item.insightValues(), "post_impressions"));
            case REACH -> Comparator.comparingLong(item -> metric(item.insightValues(), "post_impressions_unique"));
            case LIKES -> Comparator.comparingLong(MetaApiModels.FacebookContentItem::reactions);
            case COMMENTS -> Comparator.comparingLong(MetaApiModels.FacebookContentItem::comments);
            case SHARES -> Comparator.comparingLong(MetaApiModels.FacebookContentItem::shares);
            case ENGAGEMENT -> Comparator.comparingLong(item -> item.reactions() + item.comments() + item.shares()
                    + metric(item.insightValues(), "post_clicks"));
            case SAVES -> Comparator.comparingLong(item -> 0L);
            case PUBLISHED_AT -> Comparator.comparing(item -> instant(item.createdTime()), Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        return order == MetaSortOrder.ASC ? comparator : comparator.reversed();
    }

    private Comparator<MetaApiModels.FacebookVideoItem> videoComparator(MetaSortBy sortBy, MetaSortOrder order) {
        MetaSortBy normalized = sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy;
        Comparator<MetaApiModels.FacebookVideoItem> comparator = switch (normalized) {
            case VIEWS -> Comparator.comparingLong(item -> metric(item.insightValues(), "post_video_views"));
            case REACH -> Comparator.comparingLong(item -> metric(item.insightValues(), "post_impressions_unique"));
            case ENGAGEMENT -> Comparator.comparingLong(item -> metric(item.insightValues(), "post_video_social_actions_count_unique"));
            case LIKES, COMMENTS, SHARES, SAVES -> Comparator.comparingLong(item -> 0L);
            case PUBLISHED_AT -> Comparator.comparing(item -> instant(item.createdTime()), Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        return order == MetaSortOrder.ASC ? comparator : comparator.reversed();
    }

    private MetaApiModels.FacebookContentListResponse pagePosts(MetaApiModels.ReportPeriod period,
                                                                 FacebookContentType type,
                                                                 String query,
                                                                 int page,
                                                                 int size,
                                                                 MetaSortBy sortBy,
                                                                 MetaSortOrder order,
                                                                 List<MetaApiModels.FacebookContentItem> items,
                                                                 List<String> warnings) {
        int from = Math.min(page * size, items.size());
        int to = Math.min(from + size, items.size());
        int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / size);
        return new MetaApiModels.FacebookContentListResponse(
                period, type, query, page, size, items.size(), totalPages,
                sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy,
                order == null ? MetaSortOrder.DESC : order,
                List.copyOf(items.subList(from, to)), List.copyOf(warnings), Instant.now()
        );
    }

    private MetaApiModels.FacebookVideoListResponse pageVideos(MetaApiModels.ReportPeriod period,
                                                                boolean reelsOnly,
                                                                String query,
                                                                int page,
                                                                int size,
                                                                MetaSortBy sortBy,
                                                                MetaSortOrder order,
                                                                List<MetaApiModels.FacebookVideoItem> items,
                                                                List<String> warnings) {
        int from = Math.min(page * size, items.size());
        int to = Math.min(from + size, items.size());
        int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / size);
        return new MetaApiModels.FacebookVideoListResponse(
                period, reelsOnly, query, page, size, items.size(), totalPages,
                sortBy == null ? MetaSortBy.PUBLISHED_AT : sortBy,
                order == null ? MetaSortOrder.DESC : order,
                List.copyOf(items.subList(from, to)), List.copyOf(warnings), Instant.now()
        );
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
            throw new IllegalArgumentException("ID Meta non valido");
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
