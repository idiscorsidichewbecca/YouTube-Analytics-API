package it.chewbecca.youtubeanalytics.meta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.meta")
public record MetaProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String configurationId,
        URI redirectUri,
        String graphApiVersion,
        String expectedPageId,
        String expectedInstagramAccountId,
        int defaultReportDays,
        int maxInsightRangeDays,
        int maxContentItems,
        int tokenExpiryFallbackDays
) {
    public String normalizedGraphApiVersion() {
        if (!StringUtils.hasText(graphApiVersion)) {
            return "v25.0";
        }
        return graphApiVersion.startsWith("v") ? graphApiVersion : "v" + graphApiVersion;
    }

    public Duration tokenExpiryFallback() {
        return Duration.ofDays(Math.max(1, tokenExpiryFallbackDays));
    }

    public void requireConfigured() {
        if (!enabled) {
            throw new IllegalStateException("L'integrazione Meta è disabilitata. Imposta META_ENABLED=true.");
        }
        if (!StringUtils.hasText(appId)
                || !StringUtils.hasText(appSecret)
                || !StringUtils.hasText(configurationId)
                || redirectUri == null) {
            throw new IllegalStateException(
                    "Configurazione Meta incompleta: servono META_APP_ID, META_APP_SECRET, META_CONFIG_ID e META_REDIRECT_URI"
            );
        }
    }
}
