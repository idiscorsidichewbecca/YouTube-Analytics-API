package it.chewbecca.youtubeanalytics.meta.api.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class MetaApiModels {

    private MetaApiModels() {
    }

    public record ReportPeriod(LocalDate startDate, LocalDate endDate, String analyticsTimeZone) {
    }

    public record InsightValue(String endTime, JsonNode value) {
    }

    public record InsightMetric(
            String name,
            String period,
            String title,
            String description,
            List<InsightValue> values,
            JsonNode totalValue
    ) {
    }

    public record InsightsResponse(
            String platform,
            String objectId,
            ReportPeriod reportPeriod,
            List<InsightMetric> metrics,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record TrendPoint(String period, Map<String, JsonNode> metrics) {
    }

    public record TrendResponse(
            String platform,
            String objectId,
            ReportPeriod reportPeriod,
            List<TrendPoint> points,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record FacebookPage(
            String id,
            String name,
            String username,
            String link,
            String about,
            String category,
            Long followersCount,
            Long fanCount,
            String verificationStatus,
            String pictureUrl
    ) {
    }

    public record InstagramAccount(
            String id,
            String username,
            String name,
            String biography,
            String website,
            String profilePictureUrl,
            Long followersCount,
            Long followsCount,
            Long mediaCount
    ) {
    }

    public record FacebookSummary(
            FacebookPage page,
            ReportPeriod reportPeriod,
            Map<String, JsonNode> latestMetrics,
            Map<String, JsonNode> periodTotals,
            List<InsightMetric> insights,
            long postsPublished,
            long videosPublished,
            long reelsPublished,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record InstagramSummary(
            InstagramAccount account,
            ReportPeriod reportPeriod,
            Map<String, JsonNode> latestMetrics,
            Map<String, JsonNode> periodTotals,
            List<InsightMetric> insights,
            long mediaPublished,
            long reelsPublished,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record FacebookContentItem(
            String id,
            String message,
            String createdTime,
            String permalinkUrl,
            String fullPicture,
            String statusType,
            FacebookContentType contentType,
            long reactions,
            long comments,
            long shares,
            JsonNode attachments,
            Map<String, JsonNode> insightValues,
            List<InsightMetric> insights
    ) {
    }

    public record FacebookContentListResponse(
            ReportPeriod reportPeriod,
            FacebookContentType type,
            String query,
            int page,
            int size,
            long totalElements,
            int totalPages,
            MetaSortBy sortBy,
            MetaSortOrder order,
            List<FacebookContentItem> items,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record FacebookVideoItem(
            String id,
            String title,
            String description,
            String createdTime,
            String updatedTime,
            String permalinkUrl,
            String pictureUrl,
            Double lengthSeconds,
            String liveStatus,
            boolean reel,
            Map<String, JsonNode> insightValues,
            List<InsightMetric> insights
    ) {
    }

    public record FacebookVideoListResponse(
            ReportPeriod reportPeriod,
            boolean reelsOnly,
            String query,
            int page,
            int size,
            long totalElements,
            int totalPages,
            MetaSortBy sortBy,
            MetaSortOrder order,
            List<FacebookVideoItem> items,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record InstagramChildMedia(
            String id,
            String mediaType,
            String mediaUrl,
            String thumbnailUrl,
            String permalink,
            String timestamp
    ) {
    }

    public record InstagramMediaItem(
            String id,
            String caption,
            String mediaType,
            String mediaProductType,
            InstagramMediaType contentType,
            String mediaUrl,
            String thumbnailUrl,
            String permalink,
            String timestamp,
            String username,
            Long likeCount,
            Long commentsCount,
            List<InstagramChildMedia> children,
            Map<String, JsonNode> insightValues,
            List<InsightMetric> insights
    ) {
    }

    public record InstagramMediaListResponse(
            ReportPeriod reportPeriod,
            InstagramMediaType type,
            String query,
            int page,
            int size,
            long totalElements,
            int totalPages,
            MetaSortBy sortBy,
            MetaSortOrder order,
            List<InstagramMediaItem> items,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record MetaOverview(
            FacebookSummary facebook,
            InstagramSummary instagram,
            Instant generatedAt
    ) {
    }
}
