package it.chewbecca.youtubeanalytics.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        boolean enabled,
        String botToken,
        String botUsername,
        String webhookSecret,
        long allowedChatId
) {

    public String normalizedBotUsername() {
        if (botUsername == null) {
            return "";
        }
        String normalized = botUsername.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
