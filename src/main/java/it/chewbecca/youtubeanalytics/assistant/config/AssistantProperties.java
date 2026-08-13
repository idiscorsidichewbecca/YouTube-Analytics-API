package it.chewbecca.youtubeanalytics.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(
        boolean enabled,
        String apiKey,
        String model,
        String actionsBaseUrl,
        int maxOutputTokens,
        int maxToolRounds
) {
}
