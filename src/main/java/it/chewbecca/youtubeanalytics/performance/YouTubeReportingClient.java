package it.chewbecca.youtubeanalytics.performance;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.client.YouTubeStudioClient.REPORTING;

@Component
public class YouTubeReportingClient {
    public static final String TYPE = "channel_reach_basic_a1";
    private static final int MAX_CSV_BYTES = 32 * 1024 * 1024;
    private final YouTubeStudioClient google;
    private final GoogleAccessTokenService tokens;
    private final RestClient downloader;

    public YouTubeReportingClient(YouTubeStudioClient google, GoogleAccessTokenService tokens) {
        this.google = google;
        this.tokens = tokens;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.downloader = RestClient.builder().requestFactory(factory).build();
    }

    public boolean supports(String type) {
        return all("reportTypes", "reportTypes").stream().anyMatch(item -> type.equals(item.path("id").asText()));
    }

    public List<String> jobs(String type) {
        return all("jobs", "jobs").stream().filter(item -> type.equals(item.path("reportTypeId").asText())
                        && item.path("expireTime").asText("").isBlank())
                .map(item -> item.path("id").asText()).sorted().toList();
    }

    public String createJob(String type) {
        return google.write(REPORTING, "jobs", Map.of(), Map.of("reportTypeId", type,
                "name", "Chewbecca Studio - " + type), false, null).path("id").asText();
    }

    public List<Report> reports(String job) {
        if (!job.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("ID reporting job non valido");
        return all("jobs/" + job + "/reports", "reports").stream().map(node -> {
            Instant start = Instant.parse(node.path("startTime").asText());
            Instant end = Instant.parse(node.path("endTime").asText());
            if (!Duration.between(start, end).equals(Duration.ofDays(1))) {
                throw new IllegalStateException("Intervallo report reach inatteso");
            }
            return new Report(node.path("id").asText(), start.atOffset(ZoneOffset.ofHours(-8)).toLocalDate(),
                    Instant.parse(node.path("createTime").asText()), node.path("downloadUrl").asText());
        }).toList();
    }

    public List<ReachCsv.VideoDay> download(String channel, Report report) {
        return download(report, reader -> ReachCsv.read(reader, channel, report.day()));
    }

    public List<EndScreenReportingRepository.VideoDay> downloadEndScreens(String channel, Report report) {
        return download(report, reader -> EndScreenReportingRepository.parse(reader, channel, report.day()));
    }

    private <T> T download(Report report, CsvDecoder<T> decoder) {
        URI url = URI.create(report.url());
        if (!"https".equals(url.getScheme()) || !"youtubereporting.googleapis.com".equals(url.getHost())
                || url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IllegalStateException("URL di download Reporting API inatteso");
        }
        return downloader.get().uri(url).headers(h -> h.setBearerAuth(tokens.getValidAccessToken()))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new GoogleApiException(response.getStatusCode().value(), "YouTube Reporting CSV", "");
                    }
                    try (var input = response.getBody()) {
                        byte[] bytes = input.readNBytes(MAX_CSV_BYTES + 1);
                        if (bytes.length > MAX_CSV_BYTES) throw new IOException("Dimensione CSV eccessiva");
                        return decoder.decode(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    private List<JsonNode> all(String path, String key) {
        List<JsonNode> result = new ArrayList<>();
        Map<String, Object> query = new HashMap<>(Map.of("pageSize", 100));
        String page;
        do {
            JsonNode response = google.get(REPORTING, path, query);
            response.path(key).forEach(result::add);
            page = response.path("nextPageToken").asText(null);
            if (page != null) query.put("pageToken", page);
        } while (page != null && !page.isBlank());
        return result;
    }

    @FunctionalInterface
    private interface CsvDecoder<T> {
        T decode(java.io.Reader reader) throws IOException;
    }

    public record Report(String id, LocalDate day, Instant created, String url) {}
}
