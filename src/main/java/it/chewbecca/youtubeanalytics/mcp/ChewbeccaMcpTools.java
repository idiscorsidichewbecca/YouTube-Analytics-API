package it.chewbecca.youtubeanalytics.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import it.chewbecca.youtubeanalytics.publishing.ShortPublicationPlanner;

/** Nessuna azione di scrittura MCP in questa prima versione. */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public final class ChewbeccaMcpTools {
    private final ShortPublicationPlanner planner;

    public ChewbeccaMcpTools(ShortPublicationPlanner planner) { this.planner = planner; }

    @org.springaicommunity.mcp.annotation.McpTool(
        name = "chewbecca_recommend_slot",
        description = "Analizza gli Shorts degli ultimi due mesi e suggerisce i migliori orari nelle successive 48 ore; stima, non dato ufficiale spettatori online")
    public ShortPublicationPlanner.Recommendation recommendSlot() { return planner.recommend(); }
}
