package it.chewbecca.youtubeanalytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        String adminUsername,
        String adminPassword,
        String gptApiKey,
        String tokenEncryptionKey,
        boolean failOnDefaultSecrets
) {
}
