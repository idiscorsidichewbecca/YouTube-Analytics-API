package it.chewbecca.youtubeanalytics.thumbnail;

import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient.VideoMetadata;
import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeCredential;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ThumbnailServiceTest {
    private static final String VIDEO = "abcdefghijk";
    private static final String CHANNEL = "UCexample";
    private final YouTubeDataClient videos = mock(YouTubeDataClient.class);
    private final GoogleAccessTokenService tokens = mock(GoogleAccessTokenService.class);
    private final YouTubeThumbnailClient youtube = mock(YouTubeThumbnailClient.class);
    private ThumbnailService service;

    @BeforeEach
    void setUp() throws IOException {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__thumbnail_drafts.sql")).execute(dataSource);
        var credential = mock(YoutubeCredential.class);
        when(credential.getChannelId()).thenReturn(CHANNEL);
        when(credential.getUploadsPlaylistId()).thenReturn("UUexample");
        when(tokens.requireCredential()).thenReturn(credential);
        var classifier = new ShortsClassifier(new YoutubeProperties(28, 180, true,
                LocalDate.of(2020, 9, 14), 500, List.of(), List.of()));
        service = new ThumbnailService(videos, tokens, classifier, youtube, JdbcClient.create(dataSource),
                Clock.fixed(Instant.parse("2026-11-25T09:00:00Z"), ZoneOffset.UTC),
                new ClassPathResource("copertine/stile-chewbecca.txt"));
    }

    @Test
    void auditDistinguishesMissingFromUnknownAndExcludesShortsAndOtherChannels() {
        when(videos.getOwnedUploadsForThumbnailReview("UUexample")).thenReturn(List.of(
                video(VIDEO, CHANNEL, 50, 1.77, false),
                video("unknownabcd", CHANNEL, 300, 1.77, null),
                video("coveredabcd", CHANNEL, 300, 1.77, true),
                video("shortsabcde", CHANNEL, 50, 0.56, false),
                video("outsidechan", "UCother", 300, 1.77, false)));

        var first = service.audit(1, 1);
        assertThat(first.longVideos()).isEqualTo(3);
        assertThat(first.missing()).isEqualTo(1);
        assertThat(first.custom()).isEqualTo(1);
        assertThat(first.unknown()).isEqualTo(1);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.videos().getFirst().thumbnailStatus()).isEqualTo("MISSING");
        assertThat(first.videos().getFirst().scheduledPublishAt()).isEqualTo("2026-11-27T17:00:00Z");
        assertThat(service.audit(2, 1).videos().getFirst().thumbnailStatus()).isEqualTo("UNKNOWN");
        verifyNoInteractions(youtube);
    }

    @Test
    void confirmedChoiceIsBoundToTheVideoAndIsIdempotent() {
        when(videos.getOwnedVideoForThumbnailReview(VIDEO)).thenReturn(video(VIDEO, CHANNEL, 500, 1.77, false));
        byte[] chosen = {2, 3};
        var preview = service.stage(VIDEO, List.of(new byte[]{1}, chosen, new byte[]{4}));
        verifyNoInteractions(youtube);

        assertThatThrownBy(() -> service.apply(preview.draftId(), "B", "CONFERMO ALTRO"))
                .isInstanceOf(IllegalArgumentException.class);
        String confirmation = "CONFERMO COPERTINA " + preview.draftId() + " B";
        assertThat(service.apply(preview.draftId(), "B", confirmation).status()).isEqualTo("APPLIED");
        assertThat(service.apply(preview.draftId(), "B", confirmation).status()).isEqualTo("ALREADY_APPLIED");
        verify(youtube).setThumbnail(VIDEO, chosen);
        verifyNoMoreInteractions(youtube);
    }

    @Test
    void aCustomThumbnailAddedAfterPreviewIsProtected() {
        when(videos.getOwnedVideoForThumbnailReview(VIDEO)).thenReturn(
                video(VIDEO, CHANNEL, 500, 1.77, false), video(VIDEO, CHANNEL, 500, 1.77, true));
        var preview = service.stage(VIDEO, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));

        assertThatThrownBy(() -> service.apply(preview.draftId(), "A", "CONFERMO COPERTINA " + preview.draftId() + " A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("già una copertina");
        verifyNoInteractions(youtube);
    }

    @Test
    void contextCanUseCaptionsForAnOwnedScheduledVideo() {
        when(videos.getOwnedVideoForThumbnailReview(VIDEO)).thenReturn(video(VIDEO, CHANNEL, 500, 1.77, false));
        when(youtube.caption(VIDEO)).thenReturn(
                new YouTubeThumbnailClient.Caption("AVAILABLE", "it", "standard", "WEBVTT", false));

        var context = service.context(VIDEO, true);
        assertThat(context.video().visibility()).isEqualTo("private");
        assertThat(context.caption().webVtt()).isEqualTo("WEBVTT");
        assertThat(context.style()).contains("giallo oro", "conduttore");
    }

    private static VideoMetadata video(String id, String owner, long seconds, double aspect, Boolean custom) {
        return new VideoMetadata(id, "Titolo", "Descrizione con capitoli",
                Instant.parse("2026-11-25T08:00:00Z"), Duration.ofSeconds(seconds), "https://i.ytimg.com/image.jpg",
                List.of(), "24", "none", "private", false, false, 0, 0, 0, true, owner,
                Instant.parse("2026-11-27T17:00:00Z"), custom, aspect);
    }
}
