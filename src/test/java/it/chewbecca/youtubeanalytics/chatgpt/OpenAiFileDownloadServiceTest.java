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

    @Test void allowsRegionalOpenAiHostsWithoutEnvironmentUpdates() {
        assertThat(service.validate(input(
                "https://sdmntprdenmarkeast.oaiusercontent.com/video.mp4?sig=hidden"))
                .getHost()).isEqualTo("sdmntprdenmarkeast.oaiusercontent.com");
        assertThat(service.validate(input(
                "https://sdmntprpolandcentral.oaiusercontent.com/video.mp4"))
                .getHost()).isEqualTo("sdmntprpolandcentral.oaiusercontent.com");
        assertThat(service.validate(input(
                "https://sdmntprjapanwest.oaiusercontent.com/video.mp4"))
                .getHost()).isEqualTo("sdmntprjapanwest.oaiusercontent.com");
    }

    @Test void rejectsOpenAiHostnameSpoofingAndUnrelatedSubdomains() {
        for (String host : List.of(
                "sdmntprpolandcentral.oaiusercontent.com.evil.example",
                "sdmntprpolandcentral.evil.oaiusercontent.com",
                "notregional.oaiusercontent.com",
                "oaiusercontent.com",
                "sdmntprpolandcentral.oaiusercontent.com.evil.com")) {
            assertThatThrownBy(() -> service.validate(input("https://" + host + "/video.mp4")))
                    .as("Host should be blocked: %s", host)
                    .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                    .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.HOST_NOT_ALLOWED);
        }
    }

    @Test void rejectsUnsafeRegionalHostUrls() {
        for (String url : List.of(
                "http://sdmntprdenmarkeast.oaiusercontent.com/video.mp4",
                "https://attacker:password@sdmntprdenmarkeast.oaiusercontent.com/video.mp4",
                "https://sdmntprdenmarkeast.oaiusercontent.com:8443/video.mp4",
                "https://sdmntprdenmarkeast.oaiusercontent.com/video.mp4#fragment")) {
            assertThatThrownBy(() -> service.validate(input(url)))
                    .as("Unsafe URL should be blocked")
                    .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                    .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.INVALID_URL);
        }
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

    @Test void distinguishesMissingUrlFromMissingOrMalformedId() {
        assertThatThrownBy(() -> service.validate(new OpenAiFileDownloadService.FileInput(
                null, "file_12345678", "video/mp4", "reel.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .hasMessageContaining("downloadUrlPresent=false")
                .hasMessageContaining("fileIdValid=true");
        assertThatThrownBy(() -> service.validate(new OpenAiFileDownloadService.FileInput(
                "https://files.oaiusercontent.com/video.mp4?sig=secret-token", null, "video/mp4", "reel.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .hasMessageContaining("downloadUrlPresent=true")
                .hasMessageContaining("fileIdPresent=false")
                .hasMessageNotContaining("secret-token");
        assertThatThrownBy(() -> service.validate(new OpenAiFileDownloadService.FileInput(
                "https://files.oaiusercontent.com/video.mp4", "invalid-secret-id", "video/mp4", "reel.mp4")))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .hasMessageContaining("fileIdPresent=true")
                .hasMessageContaining("fileIdValid=false")
                .hasMessageNotContaining("invalid-secret-id");
    }

}
