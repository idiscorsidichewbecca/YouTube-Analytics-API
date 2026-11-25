package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Component
public class ShortsClassifier {

    private final YoutubeProperties properties;

    public ShortsClassifier(YoutubeProperties properties) {
        this.properties = properties;
    }

    public Classification classify(YouTubeDataClient.VideoMetadata video) {
        return classify(video.videoId(), video.publishedAt(), video.durationSeconds(),
                video.title(), video.description(), video.tags());
    }

    public Classification classify(String videoId, Instant publishedAt, long duration,
                                   String title, String description, List<String> tags) {
        if (properties.forcedShortIds().contains(videoId)) {
            return new Classification(ContentType.SHORT, "MANUAL_OVERRIDE", "HIGH");
        }
        if (properties.forcedVideoIds().contains(videoId)) {
            return new Classification(ContentType.VIDEO, "MANUAL_OVERRIDE", "HIGH");
        }

        if (publishedAt != null) {
            LocalDate publicationDate = publishedAt
                    .atZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)
                    .toLocalDate();
            if (publicationDate.isBefore(properties.shortsDetectionStartDate())) {
                return new Classification(ContentType.VIDEO, "PUBLISHED_BEFORE_SHORTS", "HIGH");
            }
        }

        if (duration > properties.shortsDurationFallbackSeconds()) {
            return new Classification(ContentType.VIDEO, "DURATION_OVER_LIMIT", "HIGH");
        }

        String searchable = (title + " " + description + " " + String.join(" ", tags))
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
