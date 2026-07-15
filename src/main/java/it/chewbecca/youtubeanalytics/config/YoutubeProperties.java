package it.chewbecca.youtubeanalytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

@ConfigurationProperties(prefix = "app.youtube")
public record YoutubeProperties(
        int defaultReportDays,
        int shortsDurationFallbackSeconds,
        boolean shortsDurationFallbackEnabled,
        LocalDate shortsDetectionStartDate,
        int maxPageSize,
        List<String> forcedShortIds,
        List<String> forcedVideoIds
) {
    public YoutubeProperties {
        if (defaultReportDays < 1) {
            throw new IllegalArgumentException("app.youtube.default-report-days deve essere maggiore di zero");
        }
        if (shortsDurationFallbackSeconds < 1) {
            throw new IllegalArgumentException("app.youtube.shorts-duration-fallback-seconds deve essere maggiore di zero");
        }
        if (shortsDetectionStartDate == null) {
            throw new IllegalArgumentException("app.youtube.shorts-detection-start-date è obbligatoria");
        }
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("app.youtube.max-page-size deve essere maggiore di zero");
        }
        forcedShortIds = forcedShortIds == null ? List.of() : List.copyOf(forcedShortIds);
        forcedVideoIds = forcedVideoIds == null ? List.of() : List.copyOf(forcedVideoIds);
    }
}
