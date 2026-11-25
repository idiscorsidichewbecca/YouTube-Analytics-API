package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import it.chewbecca.youtubeanalytics.chatgpt.OpenAiFileDownloadService;
import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import it.chewbecca.youtubeanalytics.publishing.YouTubePlaylistService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChewbeccaMcpPublishingToolsTest {
    @Test void scheduleToolAdvertisesOfficialOpenAiFileParameterAndWriteScope() {
        var config = new ChewbeccaMcpPublishingTools();
        var tools = config.chewbeccaPublishingTools(
                mock(ShortPublishingService.class), mock(OpenAiFileDownloadService.class), new ObjectMapper(),
                "https://youtube-analytics-api.onrender.com",
                mock(YouTubePlaylistService.class));
        assertThat(tools).hasSize(2);
        var prepare = tools.get(0).tool();
        var schedule = tools.get(1).tool();
        assertThat(prepare.name()).isEqualTo("chewbecca_prepare_short");
        assertThat(prepare.inputSchema().properties()).containsKey("weekly_plan");
        assertThat(prepare.inputSchema().required()).doesNotContain("weekly_plan");
        assertThat(schedule.name()).isEqualTo("chewbecca_schedule_short");
        assertThat(schedule.meta().get("openai/fileParams")).isEqualTo(List.of("file"));
        assertThat(schedule.meta().toString()).contains("shorts:write");
        assertThat(schedule.description()).contains("NON aprire YouTube Studio");
        assertThat(schedule.description()).contains("DOWNLOAD_FAILED");
        assertThat(schedule.inputSchema().required())
                .containsExactlyInAnyOrder("draft_id", "confirmation_text", "file");
    }

    @Test
    void batchToolAcceptsUpToTenEntriesAndUsesTheSameAttachmentContract() {
        var tools = new ChewbeccaMcpPublishingTools().chewbeccaBatchTools(
                mock(ShortPublishingService.class), mock(OpenAiFileDownloadService.class), new ObjectMapper(),
                "https://youtube-analytics-api.onrender.com", mock(YouTubePlaylistService.class));

        var prepare = tools.getFirst().tool();
        var schedule = tools.getLast().tool();
        assertThat(prepare.name()).isEqualTo("chewbecca_prepare_shorts_batch");
        assertThat(prepare.inputSchema().properties().get("shorts"))
                .isInstanceOfSatisfying(Map.class, schema -> assertThat(schema.get("maxItems")).isEqualTo(10));
        assertThat(schedule.name()).isEqualTo("chewbecca_schedule_batch_short");
        assertThat(schedule.inputSchema().required())
                .containsExactlyInAnyOrder("batch_id", "position", "confirmation_text", "file");
        assertThat(schedule.meta().get("openai/fileParams")).isEqualTo(List.of("file"));
        assertThat(schedule.meta().toString()).contains("shorts:write");
    }

    @Test
    void confirmedBatchRetryReportsSavedVideoWithoutDownloadingOrUploadingAgain() {
        var publishing = mock(ShortPublishingService.class);
        var files = mock(OpenAiFileDownloadService.class);
        var playlists = mock(YouTubePlaylistService.class);
        var schedule = new ChewbeccaMcpPublishingTools().chewbeccaBatchTools(
                publishing, files, new ObjectMapper(), "https://youtube-analytics-api.onrender.com", playlists).getLast();
        var draft = mock(ShortDraft.class);
        when(draft.getStatus()).thenReturn(ShortDraft.Status.SCHEDULED);
        when(draft.getId()).thenReturn("draft-id");
        when(draft.getBatchId()).thenReturn("batch-id");
        when(draft.getBatchPosition()).thenReturn(2);
        when(draft.getYoutubeVideoId()).thenReturn("video-id");
        when(draft.getPublishAt()).thenReturn(Instant.parse("2026-11-29T17:00:00Z"));
        when(publishing.getBatchDraft("batch-id", 2)).thenReturn(draft);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test", "", List.of(new SimpleGrantedAuthority("SCOPE_shorts:write"))));
        try {
            var denied = schedule.callHandler().apply(null, new McpSchema.CallToolRequest(schedule.tool().name(),
                    Map.of("batch_id", "batch-id", "position", 2, "confirmation_text", "CONFERMO ALTRO")));
            assertThat(denied.isError()).isTrue();
            verifyNoInteractions(publishing, files, playlists);

            var result = schedule.callHandler().apply(null, new McpSchema.CallToolRequest(schedule.tool().name(),
                    Map.of("batch_id", "batch-id", "position", 2, "confirmation_text", "CONFERMO BATCH batch-id")));
            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("video-id", "SCHEDULED");
            verify(publishing).getBatchDraft("batch-id", 2);
            verifyNoMoreInteractions(publishing);
            verifyNoInteractions(files, playlists);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
