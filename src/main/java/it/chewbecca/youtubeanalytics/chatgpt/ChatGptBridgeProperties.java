package it.chewbecca.youtubeanalytics.chatgpt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "app.chatgpt-bridge")
public record ChatGptBridgeProperties(boolean enabled, String bearerKey, List<String> allowedFileHosts) {
    public ChatGptBridgeProperties {
        allowedFileHosts = allowedFileHosts == null ? List.of() : List.copyOf(allowedFileHosts);
    }
}
