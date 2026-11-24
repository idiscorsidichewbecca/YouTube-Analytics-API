package it.chewbecca.youtubeanalytics.chatgpt;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiFileDownloadServiceTest {
    private final OpenAiFileDownloadService service = new OpenAiFileDownloadService(
            new ChatGptBridgeProperties(true, "x".repeat(48),
                    List.of("files.oaiusercontent.com", "files.openai.com")));

    @Test void acceptsExpectedHttpsFileOnly() {
        var uri = service.validate(file("https://files.oaiusercontent.com/asset/short.mp4?token=abc"));
        assertThat(uri.getHost()).isEqualTo("files.oaiusercontent.com");
    }
    @Test void rejectsLocalhostAndPrivateNetwork() {
        assertThatThrownBy(() -> service.validate(file("http://localhost:8080/admin/youtube")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(file("https://127.0.0.1/private")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSubdomainsOfAllowedHosts() {
        assertThatThrownBy(() -> service.validate(file("https://files.openai.com.evil.example/short.mp4")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsCredentialsAndUnexpectedPorts() {
        assertThatThrownBy(() -> service.validate(file("https://user:pass@files.openai.com/short.mp4")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(file("https://files.openai.com:8443/short.mp4")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNonMp4AndInvalidFileIds() {
        assertThatThrownBy(() -> service.validate(new OpenAiFileDownloadService.FileInput(
                "https://files.openai.com/x", "file_1234567890", "application/pdf", "test.pdf")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(new OpenAiFileDownloadService.FileInput(
                "https://files.openai.com/x", "not-a-file-id", "video/mp4", "test.mp4")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private static OpenAiFileDownloadService.FileInput file(String url) {
        return new OpenAiFileDownloadService.FileInput(url, "file_1234567890", "video/mp4", "test.mp4");
    }
}
