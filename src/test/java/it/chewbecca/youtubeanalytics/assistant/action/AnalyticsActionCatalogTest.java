package it.chewbecca.youtubeanalytics.assistant.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsActionCatalogTest {

    @Test
    void loadsEveryGetActionFromTheGptOpenApiContract() {
        AnalyticsActionCatalog catalog = new AnalyticsActionCatalog(new ObjectMapper());

        assertThat(catalog.size()).isEqualTo(44);
        assertThat(catalog.tools()).hasSize(44);
        assertThat(catalog.require("getChannelSummary").path())
                .isEqualTo("/api/v1/channel/summary");
        assertThat(catalog.require("getContentDetails").parameters())
                .anyMatch(parameter -> parameter.name().equals("videoId")
                        && parameter.location().equals("path")
                        && parameter.required());
    }
}
