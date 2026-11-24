package it.chewbecca.youtubeanalytics.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** RFC 9728: discovery del resource server MCP. */
@RestController
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class ChewbeccaMcpMetadataController {
    private final String issuer;
    private final String resource;
    public ChewbeccaMcpMetadataController(@Value("${app.mcp.public-url}") String base) {
        this.issuer = base.replaceAll("/+$", "");
        this.resource = issuer + "/mcp";
    }
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> protectedResource() {
        return Map.of(
          "resource", resource,
          "authorization_servers", List.of(issuer),
          "scopes_supported", List.of("shorts:read"),
          "bearer_methods_supported", List.of("header")
        );
    }
}
