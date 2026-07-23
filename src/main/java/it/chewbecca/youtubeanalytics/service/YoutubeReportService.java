package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.SortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.api.model.TrendGranularity;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import it.chewbecca.youtubeanalytics.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class YoutubeReportService {

    private final YoutubeCatalogService catalogService;
    private final YouTubeAnalyticsClient analyticsClient;
    private final DateRangeResolver dateRangeResolver;
    private final ShortsClassifier shortsClassifier;
    private final YoutubeReportMapper mapper;
    private final YoutubeProperties properties;

    public YoutubeReportService(YoutubeCatalogService catalogService,
                                YouTubeAnalyticsClient analyticsClient,
                                DateRangeResolver dateRangeResolver,
                                ShortsClassifier shortsClassifier,
                                YoutubeReportMapper mapper,
                                YoutubeProperties properties) {
        this.catalogService = catalogService;
        this.analyticsClient = analyticsClient;
        this.dateRangeResolver = dateRangeResolver;
        this.shortsClassifier = shortsClassifier;
        this.mapper = mapper;
        this.properties = properties;
    }

    public ApiModels.ChannelSummaryResponse channelSummary(LocalDate startDate, LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        YouTubeDataClient.ChannelMetadata channel = catalogService.channel();
        YouTubeAnalyticsClient.AnalyticsMetrics period = analyticsClient.getChannelAggregate(range.startDate(), range.endDate());

        return new ApiModels.ChannelSummaryResponse(
                channel.channelId(),
                channel.title(),
                channel.customUrl(),
                channel.thumbnailUrl(),
                new ApiModels.LifetimeChannelMetrics(
                        channel.subscriberCount(),
                        channel.hiddenSubscriberCount(),
                        true,
                        channel.lifetimeViews(),
                        channel.videoCount()
                ),
                reportPeriod(range),
                mapper.metrics(period),
                Instant.now(),
                "Il totale iscritti della YouTube Data API può essere arrotondato; guadagni e perdite nel periodo provengono da YouTube Analytics."
        );
    }

    public ApiModels.TrendResponse channelTrend(LocalDate startDate,
                                                LocalDate endDate,
                                                TrendGranularity granularity) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<YouTubeAnalyticsClient.TrendPoint> rawPoints = analyticsClient.getChannelTrend(
                range.startDate(),
                range.endDate(),
                analyticsDimensionFor(granularity)
        );
        List<ApiModels.TrendPointResponse> points = normalizeTrend(rawPoints, granularity);
        return new ApiModels.TrendResponse("CHANNEL", null, reportPeriod(range), granularity, points);
    }

    public ApiModels.ContentListResponse content(LocalDate startDate,
                                                 LocalDate endDate,
                                                 ContentType type,
                                                 String query,
                                                 int page,
                                                 int size,
                                                 SortBy sortBy,
                                                 SortOrder order) {
        validatePage(page, size);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<YouTubeDataClient.VideoMetadata> videos = catalogService.videos();
        Map<String, YouTubeAnalyticsClient.AnalyticsMetrics> periodMetrics = analyticsClient.getAllVideoPerformance(
                videos.stream().map(YouTubeDataClient.VideoMetadata::videoId).toList(),
                range.startDate(),
                range.endDate()
        );

        List<ApiModels.ContentPerformanceItem> all = videos.stream()
                .map(video -> toItem(video, periodMetrics.getOrDefault(video.videoId(), YouTubeAnalyticsClient.AnalyticsMetrics.empty())))
                .filter(item -> type == ContentType.ALL || item.contentType() == type)
                .filter(item -> matchesQuery(item, query))
                .sorted(contentComparator(sortBy, order))
                .toList();

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = all.isEmpty() ? 0 : (all.size() + size - 1) / size;

        return new ApiModels.ContentListResponse(
                reportPeriod(range),
                type,
                query,
                page,
                size,
                all.size(),
                totalPages,
                sortBy,
                order,
                new ArrayList<>(all.subList(from, to))
        );
    }

    public ApiModels.ContentSummaryResponse contentSummary(LocalDate startDate, LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<YouTubeDataClient.VideoMetadata> videos = catalogService.videos();
        Map<String, YouTubeAnalyticsClient.AnalyticsMetrics> analyticsByVideo = analyticsClient.getAllVideoPerformance(
                videos.stream().map(YouTubeDataClient.VideoMetadata::videoId).toList(),
                range.startDate(),
                range.endDate()
        );

        Map<ContentType, List<YouTubeDataClient.VideoMetadata>> grouped = new EnumMap<>(ContentType.class);
        grouped.put(ContentType.VIDEO, new ArrayList<>());
        grouped.put(ContentType.SHORT, new ArrayList<>());
        for (YouTubeDataClient.VideoMetadata video : videos) {
            ContentType contentType = shortsClassifier.classify(video).type();
            grouped.get(contentType).add(video);
        }

        List<ApiModels.ContentTypeSummary> groups = List.of(ContentType.VIDEO, ContentType.SHORT).stream()
                .map(type -> summarizeType(type, grouped.get(type), analyticsByVideo, range))
                .toList();
        return new ApiModels.ContentSummaryResponse(reportPeriod(range), groups);
    }

    public ApiModels.ContentDetailResponse contentDetail(String videoId,
                                                         LocalDate startDate,
                                                         LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        YouTubeDataClient.VideoMetadata video = requireOwnedVideo(videoId);
        YouTubeAnalyticsClient.AnalyticsMetrics metrics =
                analyticsClient.getVideoAggregate(videoId, range.startDate(), range.endDate());
        ShortsClassifier.Classification classification = shortsClassifier.classify(video);

        return new ApiModels.ContentDetailResponse(
                video.videoId(),
                video.title(),
                video.description(),
                classification.type(),
                classification.detection(),
                classification.confidence(),
                video.publishedAt(),
                video.durationSeconds(),
                video.youtubeUrl(),
                video.thumbnailUrl(),
                video.tags(),
                video.categoryId(),
                video.liveBroadcastContent(),
                video.privacyStatus(),
                video.madeForKids(),
                video.selfDeclaredMadeForKids(),
                video.available(),
                new ApiModels.LifetimeContentMetrics(video.lifetimeViews(), video.lifetimeLikes(), video.lifetimeComments()),
                reportPeriod(range),
                mapper.metrics(metrics)
        );
    }

    public ApiModels.TrendResponse contentTrend(String videoId,
                                                LocalDate startDate,
                                                LocalDate endDate,
                                                TrendGranularity granularity) {
        requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<YouTubeAnalyticsClient.TrendPoint> rawPoints = analyticsClient.getVideoTrend(
                videoId,
                range.startDate(),
                range.endDate(),
                analyticsDimensionFor(granularity)
        );
        List<ApiModels.TrendPointResponse> points = normalizeTrend(rawPoints, granularity);
        return new ApiModels.TrendResponse("CONTENT", videoId, reportPeriod(range), granularity, points);
    }

    public ApiModels.BreakdownResponse trafficSources(String videoId,
                                                      LocalDate startDate,
                                                      LocalDate endDate) {
        requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<ApiModels.BreakdownItem> items = analyticsClient
                .getTrafficSources(videoId, range.startDate(), range.endDate())
                .stream()
                .map(mapper::breakdown)
                .toList();
        return new ApiModels.BreakdownResponse(videoId, "TRAFFIC_SOURCE", reportPeriod(range), items);
    }

    public ApiModels.BreakdownResponse countries(String videoId,
                                                 LocalDate startDate,
                                                 LocalDate endDate,
                                                 int limit) {
        requireOwnedVideo(videoId);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit deve essere compreso tra 1 e 200");
        }
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<ApiModels.BreakdownItem> items = analyticsClient
                .getCountries(videoId, range.startDate(), range.endDate(), limit)
                .stream()
                .map(mapper::breakdown)
                .toList();
        return new ApiModels.BreakdownResponse(videoId, "COUNTRY", reportPeriod(range), items);
    }

    public ApiModels.BreakdownResponse channelSubscriptionStatus(LocalDate startDate, LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        return breakdownResponse(null, "SUBSCRIBED_STATUS", range,
                analyticsClient.getSubscriptionStatus(null, range.startDate(), range.endDate()));
    }

    public ApiModels.BreakdownResponse contentSubscriptionStatus(String videoId,
                                                                 LocalDate startDate,
                                                                 LocalDate endDate) {
        requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        return breakdownResponse(videoId, "SUBSCRIBED_STATUS", range,
                analyticsClient.getSubscriptionStatus(videoId, range.startDate(), range.endDate()));
    }

    public ApiModels.BreakdownResponse channelDevices(LocalDate startDate, LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        return breakdownResponse(null, "DEVICE_TYPE", range,
                analyticsClient.getDevices(null, range.startDate(), range.endDate()));
    }

    public ApiModels.BreakdownResponse contentDevices(String videoId,
                                                      LocalDate startDate,
                                                      LocalDate endDate) {
        requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        return breakdownResponse(videoId, "DEVICE_TYPE", range,
                analyticsClient.getDevices(videoId, range.startDate(), range.endDate()));
    }

    public ApiModels.BreakdownResponse channelOperatingSystems(LocalDate startDate,
                                                               LocalDate endDate,
                                                               int limit) {
        validateBreakdownLimit(limit);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        return breakdownResponse(null, "OPERATING_SYSTEM", range,
                analyticsClient.getOperatingSystems(null, range.startDate(), range.endDate(), limit));
    }

    public ApiModels.DemographicsResponse channelDemographics(LocalDate startDate, LocalDate endDate) {
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<ApiModels.DemographicItem> items = analyticsClient
                .getDemographics(range.startDate(), range.endDate())
                .stream()
                .map(item -> new ApiModels.DemographicItem(
                        item.ageGroup(),
                        item.gender(),
                        item.viewerPercentage()
                ))
                .toList();
        return new ApiModels.DemographicsResponse(reportPeriod(range), items);
    }

    public ApiModels.RetentionResponse retention(String videoId,
                                                 LocalDate startDate,
                                                 LocalDate endDate) {
        requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<ApiModels.RetentionPoint> points = analyticsClient
                .getRetention(videoId, range.startDate(), range.endDate())
                .stream()
                .map(point -> new ApiModels.RetentionPoint(
                        point.elapsedVideoTimeRatio(),
                        point.elapsedVideoTimeRatio() * 100,
                        point.audienceWatchRatio(),
                        point.audienceWatchRatio() * 100,
                        point.relativeRetentionPerformance()
                ))
                .toList();
        return new ApiModels.RetentionResponse(videoId, reportPeriod(range), points);
    }

    public ApiModels.TrafficSourceDetailsResponse trafficSourceDetails(String videoId,
                                                                       String sourceType,
                                                                       LocalDate startDate,
                                                                       LocalDate endDate,
                                                                       int limit) {
        requireOwnedVideo(videoId);
        validateBreakdownLimit(limit);
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType è obbligatorio");
        }
        String normalizedSourceType = sourceType.trim().toUpperCase(Locale.ROOT);
        if (!normalizedSourceType.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("sourceType contiene caratteri non validi");
        }
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        List<ApiModels.TrafficSourceDetailItem> items = analyticsClient
                .getTrafficSourceDetails(
                        videoId,
                        normalizedSourceType,
                        range.startDate(),
                        range.endDate(),
                        limit
                )
                .stream()
                .map(item -> new ApiModels.TrafficSourceDetailItem(
                        item.detail(),
                        item.views(),
                        item.estimatedMinutesWatched(),
                        item.estimatedMinutesWatched() / 60.0
                ))
                .toList();
        return new ApiModels.TrafficSourceDetailsResponse(
                videoId,
                normalizedSourceType,
                reportPeriod(range),
                items
        );
    }

    private ApiModels.BreakdownResponse breakdownResponse(
            String videoId,
            String dimension,
            DateRangeResolver.DateRange range,
            List<YouTubeAnalyticsClient.BreakdownMetric> values) {
        return new ApiModels.BreakdownResponse(
                videoId,
                dimension,
                reportPeriod(range),
                values.stream().map(mapper::breakdown).toList()
        );
    }

    private void validateBreakdownLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit deve essere compreso tra 1 e 200");
        }
    }

    private ApiModels.ContentTypeSummary summarizeType(
            ContentType type,
            List<YouTubeDataClient.VideoMetadata> videos,
            Map<String, YouTubeAnalyticsClient.AnalyticsMetrics> analyticsByVideo,
            DateRangeResolver.DateRange range) {
        long lifetimeViews = 0;
        long lifetimeLikes = 0;
        long lifetimeComments = 0;
        long publishedInPeriod = 0;
        long engagedViews = 0;
        long views = 0;
        long comments = 0;
        long likes = 0;
        long shares = 0;
        double minutesWatched = 0;
        double weightedViewPercentage = 0;
        long subscribersGained = 0;
        long subscribersLost = 0;

        for (YouTubeDataClient.VideoMetadata video : videos) {
            lifetimeViews += video.lifetimeViews();
            lifetimeLikes += video.lifetimeLikes();
            lifetimeComments += video.lifetimeComments();
            if (video.publishedAt() != null) {
                LocalDate publicationDate = video.publishedAt()
                        .atZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)
                        .toLocalDate();
                if (!publicationDate.isBefore(range.startDate()) && !publicationDate.isAfter(range.endDate())) {
                    publishedInPeriod++;
                }
            }

            YouTubeAnalyticsClient.AnalyticsMetrics metrics = analyticsByVideo.getOrDefault(
                    video.videoId(),
                    YouTubeAnalyticsClient.AnalyticsMetrics.empty()
            );
            engagedViews += metrics.engagedViews();
            views += metrics.views();
            comments += metrics.comments();
            likes += metrics.likes();
            shares += metrics.shares();
            minutesWatched += metrics.estimatedMinutesWatched();
            weightedViewPercentage += metrics.averageViewPercentage() * metrics.views();
            subscribersGained += metrics.subscribersGained();
            subscribersLost += metrics.subscribersLost();
        }

        double averageViewDuration = views == 0 ? 0 : minutesWatched * 60.0 / views;
        double averageViewPercentage = views == 0 ? 0 : weightedViewPercentage / views;
        YouTubeAnalyticsClient.AnalyticsMetrics aggregate = new YouTubeAnalyticsClient.AnalyticsMetrics(
                engagedViews,
                views,
                comments,
                likes,
                shares,
                minutesWatched,
                averageViewDuration,
                averageViewPercentage,
                subscribersGained,
                subscribersLost
        );

        return new ApiModels.ContentTypeSummary(
                type,
                videos.size(),
                publishedInPeriod,
                new ApiModels.LifetimeContentMetrics(lifetimeViews, lifetimeLikes, lifetimeComments),
                mapper.metrics(aggregate)
        );
    }

    private boolean matchesQuery(ApiModels.ContentPerformanceItem item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return item.videoId().toLowerCase(Locale.ROOT).contains(normalized)
                || item.title().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private ApiModels.ContentPerformanceItem toItem(YouTubeDataClient.VideoMetadata video,
                                                    YouTubeAnalyticsClient.AnalyticsMetrics periodMetrics) {
        ShortsClassifier.Classification classification = shortsClassifier.classify(video);
        return new ApiModels.ContentPerformanceItem(
                video.videoId(),
                video.title(),
                classification.type(),
                classification.detection(),
                classification.confidence(),
                video.publishedAt(),
                video.durationSeconds(),
                video.youtubeUrl(),
                video.thumbnailUrl(),
                video.privacyStatus(),
                new ApiModels.LifetimeContentMetrics(video.lifetimeViews(), video.lifetimeLikes(), video.lifetimeComments()),
                mapper.metrics(periodMetrics)
        );
    }

    private Comparator<ApiModels.ContentPerformanceItem> contentComparator(SortBy sortBy, SortOrder order) {
        Comparator<ApiModels.ContentPerformanceItem> comparator = switch (sortBy) {
            case VIEWS -> Comparator.comparingLong(item -> item.periodMetrics().views());
            case ENGAGED_VIEWS -> Comparator.comparingLong(item -> item.periodMetrics().engagedViews());
            case LIKES -> Comparator.comparingLong(item -> item.periodMetrics().likes());
            case COMMENTS -> Comparator.comparingLong(item -> item.periodMetrics().comments());
            case SHARES -> Comparator.comparingLong(item -> item.periodMetrics().shares());
            case WATCH_TIME -> Comparator.comparingDouble(item -> item.periodMetrics().estimatedMinutesWatched());
            case AVERAGE_VIEW_PERCENTAGE -> Comparator.comparingDouble(item -> item.periodMetrics().averageViewPercentage());
            case SUBSCRIBERS_GAINED -> Comparator.comparingLong(item -> item.periodMetrics().subscribersGained());
            case PUBLISHED_AT -> Comparator.comparing(
                    ApiModels.ContentPerformanceItem::publishedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
        };
        return order == SortOrder.DESC ? comparator.reversed() : comparator;
    }

    private YouTubeDataClient.VideoMetadata requireOwnedVideo(String videoId) {
        YouTubeDataClient.VideoMetadata video = catalogService.video(videoId);
        if (video == null) {
            throw new ResourceNotFoundException("Il video " + videoId + " non appartiene al canale YouTube collegato");
        }
        return video;
    }

    private String analyticsDimensionFor(TrendGranularity granularity) {
        /*
         * La dimensione YouTube Analytics "month" accetta solo startDate ed endDate
         * impostate al primo giorno del mese. Per mantenere esattamente il periodo
         * richiesto dal chiamante, recuperiamo i dati giornalieri e li raggruppiamo
         * in Java quando viene richiesta la granularità mensile.
         */
        return granularity == TrendGranularity.MONTH ? "day" : granularity.analyticsDimension();
    }

    private List<ApiModels.TrendPointResponse> normalizeTrend(
            List<YouTubeAnalyticsClient.TrendPoint> rawPoints,
            TrendGranularity granularity) {
        if (granularity == TrendGranularity.DAY) {
            return rawPoints.stream()
                    .map(point -> new ApiModels.TrendPointResponse(point.period(), mapper.metrics(point.metrics())))
                    .toList();
        }

        Map<YearMonth, MonthlyMetricsAccumulator> monthly = new LinkedHashMap<>();
        for (YouTubeAnalyticsClient.TrendPoint point : rawPoints) {
            LocalDate day = LocalDate.parse(point.period());
            monthly.computeIfAbsent(YearMonth.from(day), ignored -> new MonthlyMetricsAccumulator())
                    .add(point.metrics());
        }

        return monthly.entrySet().stream()
                .map(entry -> new ApiModels.TrendPointResponse(
                        entry.getKey().toString(),
                        mapper.metrics(entry.getValue().toMetrics())
                ))
                .toList();
    }

    private static final class MonthlyMetricsAccumulator {
        private long engagedViews;
        private long views;
        private long comments;
        private long likes;
        private long shares;
        private double estimatedMinutesWatched;
        private long subscribersGained;
        private long subscribersLost;
        private double weightedAverageViewDuration;
        private double weightedAverageViewPercentage;
        private long averageWeight;

        private void add(YouTubeAnalyticsClient.AnalyticsMetrics metrics) {
            engagedViews += metrics.engagedViews();
            views += metrics.views();
            comments += metrics.comments();
            likes += metrics.likes();
            shares += metrics.shares();
            estimatedMinutesWatched += metrics.estimatedMinutesWatched();
            subscribersGained += metrics.subscribersGained();
            subscribersLost += metrics.subscribersLost();

            long weight = metrics.engagedViews() > 0 ? metrics.engagedViews() : metrics.views();
            if (weight > 0) {
                weightedAverageViewDuration += metrics.averageViewDurationSeconds() * weight;
                weightedAverageViewPercentage += metrics.averageViewPercentage() * weight;
                averageWeight += weight;
            }
        }

        private YouTubeAnalyticsClient.AnalyticsMetrics toMetrics() {
            double averageViewDuration = averageWeight == 0
                    ? 0
                    : weightedAverageViewDuration / averageWeight;
            double averageViewPercentage = averageWeight == 0
                    ? 0
                    : weightedAverageViewPercentage / averageWeight;

            return new YouTubeAnalyticsClient.AnalyticsMetrics(
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
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page non può essere negativo");
        }
        if (size < 1 || size > properties.maxPageSize()) {
            throw new IllegalArgumentException("size deve essere compreso tra 1 e " + properties.maxPageSize());
        }
    }

    private ApiModels.ReportPeriod reportPeriod(DateRangeResolver.DateRange range) {
        return new ApiModels.ReportPeriod(
                range.startDate(),
                range.endDate(),
                DateRangeResolver.YOUTUBE_ANALYTICS_ZONE.getId()
        );
    }
}
