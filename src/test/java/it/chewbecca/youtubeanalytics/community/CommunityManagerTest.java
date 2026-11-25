package it.chewbecca.youtubeanalytics.community;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeCredential;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static it.chewbecca.youtubeanalytics.community.CommunityReplyRepository.*;
import static it.chewbecca.youtubeanalytics.community.YouTubeCommentsClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityManagerTest {
    private final YouTubeCommentsClient youtube = mock(YouTubeCommentsClient.class);
    private final CommunityReplyRepository store = mock(CommunityReplyRepository.class);
    private final GoogleAccessTokenService tokens = mock(GoogleAccessTokenService.class);
    private final Instant now = Instant.parse("2026-11-25T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Item item = new Item(1, "thread", "root", "root", "ghijklmn-01", "Titolo",
            "autore", "originale", "Risposta", "HIGH", null, "APPROVED", null);
    private final ThreadFixture fixture = new ThreadFixture(new YouTubeCommentsClient.Thread("thread", "owner",
            item.videoId(), true, 0, new Comment("root", "viewer", "autore", "originale", now, null, "published"), List.of()));

    @Test
    void publishesOnlyApprovedRevisionAndSuppressesDuplicate() {
        var manager = manager(false);
        Batch batch = batch(item);
        when(store.get("owner", "batch")).thenReturn(batch);
        when(youtube.replies(fixture.thread(), true)).thenReturn(List.of(
                new Comment("owned-reply", "owner", "Canale", "Esiste già", now, "root", "published")));
        when(store.claim("owner", "batch", 1, item)).thenReturn(true);
        assertThatThrownBy(() -> manager.publish("batch", 1, "inventato", false))
                .isInstanceOf(IllegalArgumentException.class);
        var result = manager.publish("batch", 1, batch.confirmation(), false);
        assertThat(result.outcomes().getFirst().status()).isEqualTo("ALREADY_REPLIED");
        verify(youtube, never()).insertReply(anyString(), anyString());
        verify(store).finish("owner", "batch", item, "ALREADY_REPLIED", "owned-reply", true);
    }

    @Test
    void classifiesTimeoutAsUncertainAndLaterReconcilesWithoutPostingAgain() {
        var manager = manager(false);
        var unknown = new Item(1, "thread", "root", "root", item.videoId(), "Titolo", "autore", "originale",
                "Risposta", "HIGH", null, "UNKNOWN", null);
        var batch = batch(item);
        when(store.get("owner", "batch")).thenReturn(batch, batch(unknown), batch(unknown), batch(unknown));
        when(store.claim("owner", "batch", 1, item)).thenReturn(true);
        when(youtube.replies(fixture.thread(), true)).thenReturn(List.of(), List.of(
                new Comment("verified", "owner", "Canale", "Risposta", now, "root", "published")));
        when(youtube.insertReply("root", "Risposta")).thenThrow(new ResourceAccessException("timeout"));
        assertThat(manager.publish("batch", 1, batch.confirmation(), false).outcomes().getFirst().status())
                .isEqualTo("UNKNOWN");
        assertThat(manager.publish("batch", 1, batch.confirmation(), false).outcomes().getFirst().status())
                .isEqualTo("PUBLISHED");
        verify(youtube, times(1)).insertReply("root", "Risposta");
    }

    @Test
    void dryRunDoesNotClaimOrWrite() {
        var manager = manager(true);
        var batch = batch(item);
        when(store.get("owner", "batch")).thenReturn(batch);
        assertThat(manager.publish("batch", 1, batch.confirmation(), false).outcomes().getFirst().status())
                .isEqualTo("WOULD_PUBLISH");
        verify(store, never()).claim(anyString(), anyString(), anyInt(), any());
        verify(youtube, never()).insertReply(anyString(), anyString());
    }

    private CommunityManager manager(boolean dryRun) {
        var credential = mock(YoutubeCredential.class);
        when(credential.getChannelId()).thenReturn("owner");
        when(tokens.requireCredential()).thenReturn(credential);
        when(youtube.threadById("thread")).thenReturn(fixture.thread());
        return new CommunityManager(youtube, mock(YouTubeDataClient.class), mock(ShortsClassifier.class), tokens,
                store, clock, new SimpleMeterRegistry(), dryRun);
    }

    private Batch batch(Item item) {
        return new Batch("batch", 1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), List.of(item));
    }

    private record ThreadFixture(YouTubeCommentsClient.Thread thread) {}
}
