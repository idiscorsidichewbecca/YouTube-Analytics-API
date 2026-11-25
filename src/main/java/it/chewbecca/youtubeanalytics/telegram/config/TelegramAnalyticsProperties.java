package it.chewbecca.youtubeanalytics.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.telegram-analytics")
public record TelegramAnalyticsProperties(
        String restartDates,
        int restartWindowDays
) {
}
