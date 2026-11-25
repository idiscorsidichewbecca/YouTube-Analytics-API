package it.chewbecca.youtubeanalytics.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Diagnostica di avvio read-only: non registra credenziali e token nei log. */
@Component
public final class McpStartupDiagnostics implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(McpStartupDiagnostics.class);

    private final Environment environment;
    private final ApplicationContext context;

    public McpStartupDiagnostics(Environment environment, ApplicationContext context) {
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String enabled = environment.getProperty("app.mcp.enabled", "(missing)");
        String mcpServerEnabled = environment.getProperty("spring.ai.mcp.server.enabled", "(missing)");
        boolean metadataController = context.containsBean("chewbeccaMcpMetadataController");
        boolean oauthConfig = context.containsBean("chewbeccaMcpOAuthConfig");
        log.info("MCP_DIAGNOSTICS app.mcp.enabled={} spring.ai.mcp.server.enabled={} metadataController={} oauthConfig={}",
                enabled, mcpServerEnabled, metadataController, oauthConfig);
        if (Boolean.parseBoolean(enabled) && (!metadataController || !oauthConfig)) {
            log.error("MCP_DIAGNOSTICS enabled but metadata controller or OAuth configuration bean is absent");
        }
    }
}
