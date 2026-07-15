package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;

@Component
public class ShortsClassifier {

    private final YoutubeProperties properties;

    public ShortsClassifier(YoutubeProperties properties) {
        this.properties = properties;
    }

    public Classification classify(YouTubeDataClient.VideoMetadata video) {
        if (properties.forcedShortIds().contains(video.videoId())) {
            return new Classification(ContentType.SHORT, "MANUAL_OVERRIDE", "HIGH");
        }
        if (properties.forcedVideoIds().contains(video.videoId())) {
            return new Classification(ContentType.VIDEO, "MANUAL_OVERRIDE", "HIGH");
        }

        if (video.publishedAt() != null) {
            LocalDate publicationDate = video.publishedAt()
                    .atZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)
                    .toLocalDate();
            if (publicationDate.isBefore(properties.shortsDetectionStartDate())) {
                return new Classification(ContentType.VIDEO, "PUBLISHED_BEFORE_SHORTS", "HIGH");
            }
        }

        long duration = video.durationSeconds();
        if (duration > properties.shortsDurationFallbackSeconds()) {
            return new Classification(ContentType.VIDEO, "DURATION_OVER_LIMIT", "HIGH");
        }

        String searchable = (video.title() + " " + video.description() + " " + String.join(" ", video.tags()))
                .toLowerCase(Locale.ROOT);
        if (searchable.contains("#shorts") || searchable.contains("#short")) {
            return new Classification(ContentType.SHORT, "HASHTAG", "HIGH");
        }

        if (properties.shortsDurationFallbackEnabled() && duration > 0) {
            return new Classification(ContentType.SHORT, "DURATION_FALLBACK", "MEDIUM");
        }
        return new Classification(ContentType.VIDEO, "NO_SHORT_SIGNAL", "MEDIUM");
    }

    public record Classification(ContentType type, String detection, String confidence) {
    }
}
