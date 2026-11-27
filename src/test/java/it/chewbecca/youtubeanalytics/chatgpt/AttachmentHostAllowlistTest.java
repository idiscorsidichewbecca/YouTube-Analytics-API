package it.chewbecca.youtubeanalytics.chatgpt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AttachmentHostAllowlistTest {
    private AttachmentHostAllowlist hosts;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V16__attachment_host_allowlist.sql")).execute(ds);
        hosts = new AttachmentHostAllowlist(new JdbcTemplate(ds));
    }

    @Test void seedAllowsUkWestAndNewApprovalTakesEffectWithoutRestart() {
        var policy = new OpenAiAzureFileHosts((alias, target) -> false, hosts::contains);
        var service = new OpenAiFileDownloadService(
                new ChatGptBridgeProperties(false, "", List.of()), policy);
        String uk = "oaisdmntprukwest.blob.core.windows.net";
        assertThat(service.validate(input(uk)).getHost()).isEqualTo(uk);
        String next = "oaisdmntprnewregion.blob.core.windows.net";
        assertThatThrownBy(() -> service.validate(input(next)))
                .isInstanceOf(OpenAiFileDownloadService.AttachmentDownloadException.class)
                .extracting("code").isEqualTo(OpenAiFileDownloadService.DownloadError.HOST_NOT_ALLOWED);
        assertThat(hosts.approve(next, "AUTORIZZO HOST " + next, "Test evidence", "admin")).isTrue();
        assertThat(service.validate(input(next)).getHost()).isEqualTo(next);
        assertThat(hosts.approve(next, "AUTORIZZO HOST " + next, "Test evidence", "admin")).isFalse();
        assertThat(hosts.list()).hasSize(5);
    }

    @Test void cannotApproveWildcardSpoofedHostOrWithoutExactConfirmation() {
        for (String host : List.of("*.blob.core.windows.net", "127.0.0.1",
                "evil.blob.core.windows.net", "oaisdmntprukwest.blob.core.windows.net.evil.test",
                "https://oaisdmntprukwest.blob.core.windows.net")) {
            assertThatThrownBy(() -> hosts.approve(host, "AUTORIZZO HOST " + host, "Test", "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        String next = "oaisdmntprnewregion.blob.core.windows.net";
        assertThatThrownBy(() -> hosts.approve(next, "yes", "Test", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hosts.contains(next)).isFalse();
        assertThat(hosts.list()).hasSize(4);
    }

    private OpenAiFileDownloadService.FileInput input(String host) {
        return new OpenAiFileDownloadService.FileInput("https://" + host + "/video.mp4",
                "opaque-reference", "video/mp4", "video.mp4");
    }
}
