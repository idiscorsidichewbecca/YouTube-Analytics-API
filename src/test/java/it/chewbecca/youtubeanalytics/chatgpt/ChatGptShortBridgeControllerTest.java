package it.chewbecca.youtubeanalytics.chatgpt;

import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublicationPlanner;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatGptShortBridgeControllerTest {
    private final ShortPublicationPlanner planner = mock(ShortPublicationPlanner.class);
    private final ShortPublishingService publishing = mock(ShortPublishingService.class);
    private final OpenAiFileDownloadService download = mock(OpenAiFileDownloadService.class);
    private final ChatGptShortBridgeController controller = new ChatGptShortBridgeController(planner, publishing, download);

    @Test void doesNotDownloadOrUploadWithoutExplicitConfirmation() {
        var file = new OpenAiFileDownloadService.FileInput(
                "https://files.openai.com/x", "file_1234567890", "video/mp4", "short.mp4");
        var req = new ChatGptShortBridgeController.ScheduleFromChat(file,
                "non confermo", null, null, Instant.parse("2026-11-25T18:00:00Z"));
        assertThatThrownBy(() -> controller.schedule("abc", req))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publishing, download);
    }
    @Test void doesNotAttemptDownloadForAlreadyScheduledDraft() {
        ShortDraft draft = mock(ShortDraft.class);
        when(draft.getStatus()).thenReturn(ShortDraft.Status.SCHEDULED);
        when(publishing.getDraft("abc")).thenReturn(draft);
        var req = new ChatGptShortBridgeController.ScheduleFromChat(
                new OpenAiFileDownloadService.FileInput("https://files.openai.com/x",
                        "file_1234567890", "video/mp4", "short.mp4"),
                "CONFERMO abc", null, null, null);
        assertThatThrownBy(() -> controller.schedule("abc", req))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(download);
    }
}
