package it.chewbecca.youtubeanalytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "app.google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        URI redirectUri,
        String expectedChannelId,
        String loginHint
) {
}