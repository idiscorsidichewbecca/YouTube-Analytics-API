package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChewbeccaMcpPublishingToolsTest {
    @Test void scheduleToolAdvertisesOfficialOpenAiFileParameterAndWriteScope() {
        var config = new ChewbeccaMcpPublishingTools();
        var tools = config.chewbeccaPublishingTools(
                mock(ShortPublishingService.class), mock(OpenAiFileDownloadService.class), new ObjectMapper(), "https://youtube-analytics-api.onrender.com");
        assertThat(tools).hasSize(2);
        var prepare = tools.get(0).tool();
        var schedule = tools.get(1).tool();
        assertThat(prepare.name()).isEqualTo("chewbecca_prepare_short");
        assertThat(schedule.name()).isEqualTo("chewbecca_schedule_short");
        assertThat(schedule.meta().get("openai/fileParams")).isEqualTo(List.of("file"));
        assertThat(schedule.meta().toString()).contains("shorts:write");
        assertThat(schedule.description()).contains("NON aprire YouTube Studio");
        assertThat(schedule.description()).contains("DOWNLOAD_FAILED");
        assertThat(schedule.inputSchema().required())
                .containsExactlyInAnyOrder("draft_id", "confirmation_text", "file");
    }
}
