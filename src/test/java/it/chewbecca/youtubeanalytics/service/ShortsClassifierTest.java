package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortsClassifierTest {

    private final YoutubeProperties properties = new YoutubeProperties(
            28,
            180,
            true,
            LocalDate.of(2020, 9, 14),
            100,
            List.of("forced-short"),
            List.of("forced-video")
    );
    private final ShortsClassifier classifier = new ShortsClassifier(properties);

    @Test
    void classifiesLongContentAsVideo() {
        assertThat(classifier.classify(video("id", Duration.ofMinutes(8), "Titolo")).type())
                .isEqualTo(ContentType.VIDEO);
    }

    @Test
    void classifiesHashtagAsShort() {
        ShortsClassifier.Classification result = classifier.classify(video("id", Duration.ofSeconds(50), "Titolo #shorts"));
        assertThat(result.type()).isEqualTo(ContentType.SHORT);
        assertThat(result.detection()).isEqualTo("HASHTAG");
    }

    @Test
    void contentPublishedBeforeShortsIsVideo() {
        YouTubeDataClient.VideoMetadata oldVideo = new YouTubeDataClient.VideoMetadata(
                "old", "Video breve", "", Instant.parse("2019-05-01T10:00:00Z"),
                Duration.ofSeconds(45), null, List.of(), null,
                "none", "public", false, false, 0, 0, 0, true
        );
        ShortsClassifier.Classification result = classifier.classify(oldVideo);
        assertThat(result.type()).isEqualTo(ContentType.VIDEO);
        assertThat(result.detection()).isEqualTo("PUBLISHED_BEFORE_SHORTS");
    }

    @Test
    void manualVideoOverrideWins() {
        assertThat(classifier.classify(video("forced-video", Duration.ofSeconds(30), "#shorts")).type())
                .isEqualTo(ContentType.VIDEO);
    }

    private YouTubeDataClient.VideoMetadata video(String id, Duration duration, String title) {
        return new YouTubeDataClient.VideoMetadata(
                id, title, "", Instant.now(), duration, null, List.of(), null,
                "none", "public", false, false, 0, 0, 0, true
        );
    }
}
