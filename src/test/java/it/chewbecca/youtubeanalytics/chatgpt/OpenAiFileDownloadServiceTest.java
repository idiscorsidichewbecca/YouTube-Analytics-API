package it.chewbecca.youtubeanalytics.chatgpt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OpenAiFileDownloadServiceTest {
    private final OpenAiFileDownloadService service = new OpenAiFileDownloadService(
            new ChatGptBridgeProperties(false, "", List.of("files.oaiusercontent.com", "files.openai.com")));

    private OpenAiFileDownloadService.FileInput input(String url) {
        return new OpenAiFileDownloadService.FileInput(url, "file_12345678", "video/mp4", "reel.mp4");
    }

    @Test void allowsOnlyExactConfiguredHost() {
        assertThat(service.validate(input("https://files.oaiusercontent.com/a.mp4?signature=secret"))
                .getHost()).isEqualTo("files.oaiusercontent.com");
    }

    @Test void rejectsLookalikeHostname() {
        assertThatThrownBy(() -> service.validate(input("https://files.oaiusercontent.com.attacker.org/video.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.HOST_NOT_ALLOWED);
    }

    @Test void rejectsPrivateAddressEvenWhenHttps() {
        assertThatThrownBy(() -> service.validate(input("https://127.0.0.1/video.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.HOST_NOT_ALLOWED);
    }

    @Test void rejectsHttpAndCredentialBearingUrl() {
        assertThatThrownBy(() -> service.validate(input("http://files.oaiusercontent.com/video.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.INVALID_URL);
        assertThatThrownBy(() -> service.validate(input("https://user:pass@files.oaiusercontent.com/video.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.INVALID_URL);
    }

    @Test void rejectsInvalidFileId() {
        var file = new OpenAiFileDownloadService.FileInput("https://files.oaiusercontent.com/video.mp4",
                "malformed", "video/mp4", "video.mp4");
        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.INVALID_FILE);
    }
}
