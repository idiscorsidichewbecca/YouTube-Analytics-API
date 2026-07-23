package it.chewbecca.youtubeanalytics.api.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record ReportPeriod(LocalDate startDate, LocalDate endDate, String analyticsTimeZone) {
    }

    public record AnalyticsMetrics(
            long engagedViews,
            long views,
            long comments,
            long likes,
            long shares,
            double estimatedMinutesWatched,
            double estimatedHoursWatched,
            double averageViewDurationSeconds,
            double averageViewPercentage,
            long subscribersGained,
            long subscribersLost,
            long netSubscribers,
            double engagementRatePercentage
    ) {
    }

    public record LifetimeChannelMetrics(
            long subscribers,
            boolean subscribersHidden,
            boolean subscriberCountRoundedByYouTube,
            long views,
            long publishedVideos
    ) {
    }

    public record LifetimeContentMetrics(long views, long likes, long comments) {
    }

    public record ChannelSummaryResponse(
            String channelId,
            String channelTitle,
            String customUrl,
            String thumbnailUrl,
            LifetimeChannelMetrics lifetime,
            ReportPeriod period,
            AnalyticsMetrics periodMetrics,
            Instant generatedAt,
            String note
    ) {
    }

    public record TrendPointResponse(String period, AnalyticsMetrics metrics) {
    }

    public record TrendResponse(
            String scope,
            String videoId,
            ReportPeriod reportPeriod,
            TrendGranularity granularity,
            List<TrendPointResponse> points
    ) {
    }

    public record ContentPerformanceItem(
            String videoId,
            String title,
            ContentType contentType,
            String contentTypeDetection,
            String classificationConfidence,
            Instant publishedAt,
            long durationSeconds,
            String youtubeUrl,
            String thumbnailUrl,
            String privacyStatus,
            LifetimeContentMetrics lifetime,
            AnalyticsMetrics periodMetrics
    ) {
    }

    public record ContentListResponse(
            ReportPeriod period,
            ContentType type,
            String query,
            int page,
            int size,
            long totalElements,
            int totalPages,
            SortBy sortBy,
            SortOrder order,
            List<ContentPerformanceItem> items
    ) {
    }

    public record ContentTypeSummary(
            ContentType contentType,
            long totalPublished,
            long publishedInPeriod,
            LifetimeContentMetrics lifetime,
            AnalyticsMetrics periodMetrics
    ) {
    }

    public record ContentSummaryResponse(
            ReportPeriod period,
            List<ContentTypeSummary> groups
    ) {
    }

    public record ContentDetailResponse(
            String videoId,
            String title,
            String description,
            ContentType contentType,
            String contentTypeDetection,
            String classificationConfidence,
            Instant publishedAt,
            long durationSeconds,
            String youtubeUrl,
            String thumbnailUrl,
            List<String> tags,
            String categoryId,
            String liveBroadcastContent,
            String privacyStatus,
            boolean madeForKids,
            boolean selfDeclaredMadeForKids,
            boolean available,
            LifetimeContentMetrics lifetime,
            ReportPeriod period,
            AnalyticsMetrics periodMetrics
    ) {
    }

    public record BreakdownItem(
            String value,
            long engagedViews,
            long views,
            double estimatedMinutesWatched,
            double estimatedHoursWatched,
            double averageViewDurationSeconds,
            double averageViewPercentage,
            long netSubscribers
    ) {
    }

    public record BreakdownResponse(
            String videoId,
            String dimension,
            ReportPeriod period,
            List<BreakdownItem> items
    ) {
    }

    public record DemographicItem(
            String ageGroup,
            String gender,
            double viewerPercentage
    ) {
    }

    public record DemographicsResponse(
            ReportPeriod period,
            List<DemographicItem> items
    ) {
    }

    public record RetentionPoint(
            double elapsedVideoTimeRatio,
            double elapsedVideoPercentage,
            double audienceWatchRatio,
            double audienceWatchPercentage,
            double relativeRetentionPerformance
    ) {
    }

    public record RetentionResponse(
            String videoId,
            ReportPeriod period,
            List<RetentionPoint> points
    ) {
    }

    public record TrafficSourceDetailItem(
            String detail,
            long views,
            double estimatedMinutesWatched,
            double estimatedHoursWatched
    ) {
    }

    public record TrafficSourceDetailsResponse(
            String videoId,
            String sourceType,
            ReportPeriod period,
            List<TrafficSourceDetailItem> items
    ) {
    }
}
