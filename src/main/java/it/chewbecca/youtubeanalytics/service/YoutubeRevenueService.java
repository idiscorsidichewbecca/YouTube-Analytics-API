package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.RevenueSortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.api.model.TrendGranularity;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import it.chewbecca.youtubeanalytics.exception.ResourceNotFoundException;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class YoutubeRevenueService {

    private final YoutubeCatalogService catalogService;
    private final YouTubeAnalyticsClient analyticsClient;
    private final DateRangeResolver dateRangeResolver;
    private final ShortsClassifier shortsClassifier;
    private final YoutubeProperties properties;
    private final GoogleAccessTokenService accessTokenService;

    public YoutubeRevenueService(YoutubeCatalogService catalogService,
                                 YouTubeAnalyticsClient analyticsClient,
                                 DateRangeResolver dateRangeResolver,
                                 ShortsClassifier shortsClassifier,
                                 YoutubeProperties properties,
                                 GoogleAccessTokenService accessTokenService) {
        this.catalogService = catalogService;
        this.analyticsClient = analyticsClient;
        this.dateRangeResolver = dateRangeResolver;
        this.shortsClassifier = shortsClassifier;
        this.properties = properties;
        this.accessTokenService = accessTokenService;
    }

    public ApiModels.RevenueSummaryResponse summary(LocalDate startDate,
                                                    LocalDate endDate,
                                                    String currency) {
        accessTokenService.requireMonetaryAnalyticsScope();
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        String normalizedCurrency = normalizeCurrency(currency);
        YouTubeDataClient.ChannelMetadata channel = catalogService.channel();
        YouTubeAnalyticsClient.RevenueMetrics metrics = analyticsClient.getChannelRevenue(
                range.startDate(),
                range.endDate(),
                normalizedCurrency
        );

        return new ApiModels.RevenueSummaryResponse(
                channel.channelId(),
                channel.title(),
                reportPeriod(range),
                normalizedCurrency,
                toApiMetrics(metrics),
                Instant.now(),
                "I ricavi sono stime YouTube, possono essere rettificati a fine mese e non coincidono necessariamente con il pagamento AdSense definitivo."
        );
    }

    public ApiModels.RevenueContentListResponse content(LocalDate startDate,
                                                        LocalDate endDate,
                                                        ContentType type,
                                                        String query,
                                                        int page,
                                                        int size,
                                                        RevenueSortBy sortBy,
                                                        SortOrder order,
                                                        String currency) {
        accessTokenService.requireMonetaryAnalyticsScope();
        validatePage(page, size);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        String normalizedCurrency = normalizeCurrency(currency);
        List<YouTubeDataClient.VideoMetadata> videos = catalogService.videos();
        Map<String, YouTubeAnalyticsClient.RevenueMetrics> revenueByVideo = analyticsClient.getAllVideoRevenue(
                videos.stream().map(YouTubeDataClient.VideoMetadata::videoId).toList(),
                range.startDate(),
                range.endDate(),
                normalizedCurrency
        );

        List<ApiModels.RevenueContentItem> all = videos.stream()
                .map(video -> toItem(video, revenueByVideo.getOrDefault(
                        video.videoId(),
                        YouTubeAnalyticsClient.RevenueMetrics.empty()
                )))
                .filter(item -> type == ContentType.ALL || item.contentType() == type)
                .filter(item -> matchesQuery(item, query))
                .sorted(contentComparator(sortBy, order))
                .toList();

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = all.isEmpty() ? 0 : (all.size() + size - 1) / size;

        return new ApiModels.RevenueContentListResponse(
                reportPeriod(range),
                normalizedCurrency,
                type,
                query,
                page,
                size,
                all.size(),
                totalPages,
                sortBy,
                order,
                aggregate(all.stream().map(ApiModels.RevenueContentItem::metrics).toList()),
                new ArrayList<>(all.subList(from, to))
        );
    }

    public ApiModels.RevenueContentDetailResponse contentDetail(String videoId,
                                                                LocalDate startDate,
                                                                LocalDate endDate,
                                                                String currency) {
        accessTokenService.requireMonetaryAnalyticsScope();
        YouTubeDataClient.VideoMetadata video = requireOwnedVideo(videoId);
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        String normalizedCurrency = normalizeCurrency(currency);
        YouTubeAnalyticsClient.RevenueMetrics metrics = analyticsClient.getVideoRevenue(
                videoId,
                range.startDate(),
                range.endDate(),
                normalizedCurrency
        );
        ShortsClassifier.Classification classification = shortsClassifier.classify(video);

        return new ApiModels.RevenueContentDetailResponse(
                video.videoId(),
                video.title(),
                classification.type(),
                video.publishedAt(),
                video.youtubeUrl(),
                video.thumbnailUrl(),
                reportPeriod(range),
                normalizedCurrency,
                toApiMetrics(metrics)
        );
    }

    public ApiModels.RevenueTrendResponse channelTrend(LocalDate startDate,
                                                       LocalDate endDate,
                                                       TrendGranularity granularity,
                                                       String currency) {
        return trend(null, "CHANNEL", startDate, endDate, granularity, currency);
    }

    public ApiModels.RevenueTrendResponse contentTrend(String videoId,
                                                       LocalDate startDate,
                                                       LocalDate endDate,
                                                       TrendGranularity granularity,
                                                       String currency) {
        requireOwnedVideo(videoId);
        return trend(videoId, "CONTENT", startDate, endDate, granularity, currency);
    }

    private ApiModels.RevenueTrendResponse trend(String videoId,
                                                 String scope,
                                                 LocalDate startDate,
                                                 LocalDate endDate,
                                                 TrendGranularity granularity,
                                                 String currency) {
        accessTokenService.requireMonetaryAnalyticsScope();
        DateRangeResolver.DateRange range = dateRangeResolver.resolve(startDate, endDate);
        String normalizedCurrency = normalizeCurrency(currency);
        LocalDate analyticsStart = granularity == TrendGranularity.MONTH
                ? YearMonth.from(range.startDate()).atDay(1) : range.startDate();
        LocalDate analyticsEnd = granularity == TrendGranularity.MONTH
                ? YearMonth.from(range.endDate()).atDay(1) : range.endDate();
        List<YouTubeAnalyticsClient.RevenueTrendPoint> rawPoints = analyticsClient.getRevenueTrend(
                videoId,
                analyticsStart,
                analyticsEnd,
                analyticsDimensionFor(granularity),
                normalizedCurrency
        );

        return new ApiModels.RevenueTrendResponse(
                scope,
                videoId,
                reportPeriod(range),
                granularity,
                normalizedCurrency,
                normalizeTrend(rawPoints, granularity)
        );
    }

    private List<ApiModels.RevenueTrendPointResponse> normalizeTrend(
            List<YouTubeAnalyticsClient.RevenueTrendPoint> rawPoints,
            TrendGranularity granularity) {
        if (granularity == TrendGranularity.DAY || granularity == TrendGranularity.MONTH) {
            return rawPoints.stream()
                    .map(point -> new ApiModels.RevenueTrendPointResponse(
                            point.period(),
                            toApiMetrics(point.metrics())
                    ))
                    .toList();
        }

        Map<YearMonth, RevenueAccumulator> monthly = new LinkedHashMap<>();
        for (YouTubeAnalyticsClient.RevenueTrendPoint point : rawPoints) {
            LocalDate day = LocalDate.parse(point.period());
            monthly.computeIfAbsent(YearMonth.from(day), ignored -> new RevenueAccumulator())
                    .add(point.metrics());
        }
        return monthly.entrySet().stream()
                .map(entry -> new ApiModels.RevenueTrendPointResponse(
                        entry.getKey().toString(),
                        entry.getValue().toApiMetrics()
                ))
                .toList();
    }

    private ApiModels.RevenueContentItem toItem(YouTubeDataClient.VideoMetadata video,
                                                YouTubeAnalyticsClient.RevenueMetrics metrics) {
        return new ApiModels.RevenueContentItem(
                video.videoId(),
                video.title(),
                shortsClassifier.classify(video).type(),
                video.publishedAt(),
                video.youtubeUrl(),
                video.thumbnailUrl(),
                toApiMetrics(metrics)
        );
    }

    private ApiModels.RevenueMetrics toApiMetrics(YouTubeAnalyticsClient.RevenueMetrics metrics) {
        return new ApiModels.RevenueMetrics(
                metrics.views(),
                metrics.estimatedRevenue(),
                metrics.estimatedAdRevenue(),
                metrics.estimatedPremiumRevenue(),
                metrics.grossRevenue(),
                metrics.monetizedPlaybacks(),
                metrics.adImpressions(),
                metrics.playbackBasedCpm(),
                metrics.adImpressionCpm(),
                metrics.estimatedRpm(),
                metrics.monetizedPlaybackRatePercentage()
        );
    }

    private ApiModels.RevenueMetrics aggregate(List<ApiModels.RevenueMetrics> metricsList) {
        RevenueAccumulator accumulator = new RevenueAccumulator();
        for (ApiModels.RevenueMetrics metrics : metricsList) {
            accumulator.add(metrics);
        }
        return accumulator.toApiMetrics();
    }

    private boolean matchesQuery(ApiModels.RevenueContentItem item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return item.videoId().toLowerCase(Locale.ROOT).contains(normalized)
                || item.title().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private Comparator<ApiModels.RevenueContentItem> contentComparator(RevenueSortBy sortBy, SortOrder order) {
        Comparator<ApiModels.RevenueContentItem> comparator = switch (sortBy) {
            case ESTIMATED_REVENUE -> Comparator.comparingDouble(item -> item.metrics().estimatedRevenue());
            case ESTIMATED_AD_REVENUE -> Comparator.comparingDouble(item -> item.metrics().estimatedAdRevenue());
            case ESTIMATED_PREMIUM_REVENUE -> Comparator.comparingDouble(item -> item.metrics().estimatedPremiumRevenue());
            case GROSS_REVENUE -> Comparator.comparingDouble(item -> item.metrics().grossRevenue());
            case VIEWS -> Comparator.comparingLong(item -> item.metrics().views());
            case ESTIMATED_RPM -> Comparator.comparingDouble(item -> item.metrics().estimatedRpm());
            case MONETIZED_PLAYBACKS -> Comparator.comparingLong(item -> item.metrics().monetizedPlaybacks());
            case AD_IMPRESSIONS -> Comparator.comparingLong(item -> item.metrics().adImpressions());
            case PUBLISHED_AT -> Comparator.comparing(
                    ApiModels.RevenueContentItem::publishedAt,
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

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "EUR";
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency deve essere un codice ISO 4217 di tre lettere, ad esempio EUR o USD");
        }
        return normalized;
    }

    private String analyticsDimensionFor(TrendGranularity granularity) {
        return granularity.analyticsDimension();
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

    private static final class RevenueAccumulator {
        private long views;
        private double estimatedRevenue;
        private double estimatedAdRevenue;
        private double estimatedPremiumRevenue;
        private double grossRevenue;
        private long monetizedPlaybacks;
        private long adImpressions;

        private void add(YouTubeAnalyticsClient.RevenueMetrics metrics) {
            views += metrics.views();
            estimatedRevenue += metrics.estimatedRevenue();
            estimatedAdRevenue += metrics.estimatedAdRevenue();
            estimatedPremiumRevenue += metrics.estimatedPremiumRevenue();
            grossRevenue += metrics.grossRevenue();
            monetizedPlaybacks += metrics.monetizedPlaybacks();
            adImpressions += metrics.adImpressions();
        }

        private void add(ApiModels.RevenueMetrics metrics) {
            views += metrics.views();
            estimatedRevenue += metrics.estimatedRevenue();
            estimatedAdRevenue += metrics.estimatedAdRevenue();
            estimatedPremiumRevenue += metrics.estimatedPremiumRevenue();
            grossRevenue += metrics.grossRevenue();
            monetizedPlaybacks += metrics.monetizedPlaybacks();
            adImpressions += metrics.adImpressions();
        }

        private ApiModels.RevenueMetrics toApiMetrics() {
            double playbackBasedCpm = monetizedPlaybacks == 0 ? 0 : grossRevenue * 1000.0 / monetizedPlaybacks;
            double adImpressionCpm = adImpressions == 0 ? 0 : grossRevenue * 1000.0 / adImpressions;
            double estimatedRpm = views == 0 ? 0 : estimatedRevenue * 1000.0 / views;
            double monetizedPlaybackRate = views == 0 ? 0 : monetizedPlaybacks * 100.0 / views;
            return new ApiModels.RevenueMetrics(
                    views,
                    estimatedRevenue,
                    estimatedAdRevenue,
                    estimatedPremiumRevenue,
                    grossRevenue,
                    monetizedPlaybacks,
                    adImpressions,
                    playbackBasedCpm,
                    adImpressionCpm,
                    estimatedRpm,
                    monetizedPlaybackRate
            );
        }
    }
}
