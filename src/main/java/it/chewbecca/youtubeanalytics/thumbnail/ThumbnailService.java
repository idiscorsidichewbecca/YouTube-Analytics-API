package it.chewbecca.youtubeanalytics.thumbnail;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient.VideoMetadata;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ThumbnailService {
    private static final LocalDate THREE_MINUTE_SHORTS = LocalDate.of(2024, 10, 15);
    private static final ZoneId YOUTUBE_ZONE = ZoneId.of("America/Los_Angeles");

    private final YouTubeDataClient videos;
    private final GoogleAccessTokenService tokens;
    private final ShortsClassifier classifier;
    private final YouTubeThumbnailClient youtube;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final String style;

    public ThumbnailService(YouTubeDataClient videos, GoogleAccessTokenService tokens, ShortsClassifier classifier,
                            YouTubeThumbnailClient youtube, JdbcClient jdbc, Clock clock,
                            @Value("classpath:copertine/stile-chewbecca.txt") Resource style) throws IOException {
        this.videos = videos;
        this.tokens = tokens;
        this.classifier = classifier;
        this.youtube = youtube;
        this.jdbc = jdbc;
        this.clock = clock;
        this.style = style.getContentAsString(StandardCharsets.UTF_8);
    }

    public Audit audit(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Pagina da 1 in poi, da 1 a 50 risultati");
        }
        var credential = tokens.requireCredential();
        List<VideoMetadata> uploads = videos.getOwnedUploadsForThumbnailReview(credential.getUploadsPlaylistId());
        List<Row> longVideos = uploads.stream()
                .filter(v -> credential.getChannelId().equals(v.channelId()) && isLongVideo(v))
                .map(ThumbnailService::row)
                .toList();
        int custom = (int) longVideos.stream().filter(v -> "CUSTOM".equals(v.thumbnailStatus())).count();
        int review = (int) longVideos.stream().filter(v -> "UNKNOWN".equals(v.thumbnailStatus())).count();
        var remaining = longVideos.stream().filter(v -> !"CUSTOM".equals(v.thumbnailStatus()))
                .sorted(Comparator.comparing((Row v) -> !"MISSING".equals(v.thumbnailStatus())))
                .skip((page - 1L) * limit).limit(limit)
                .toList();
        return new Audit(longVideos.size(), longVideos.size() - custom - review, review, custom, page,
                (long) page * limit < longVideos.size() - custom, remaining);
    }

    public Context context(String videoId, boolean withCaption) {
        VideoMetadata video = requireLongVideo(videoId);
        return new Context(row(video), video.description(),
                withCaption ? youtube.caption(videoId) : YouTubeThumbnailClient.Caption.unavailable("NOT_REQUESTED"),
                style);
    }

    public DraftPreview stage(String videoId, List<byte[]> photos) {
        VideoMetadata video = requireLongVideo(videoId);
        requireMissing(video);
        if (photos.size() != 3 || photos.stream().anyMatch(p -> p == null || p.length == 0)) {
            throw new IllegalArgumentException("Servono tre copertine distinte, A, B e C");
        }
        List<String> encoded = photos.stream().map(Base64.getEncoder()::encodeToString).toList();
        if (encoded.stream().distinct().count() != 3) {
            throw new IllegalArgumentException("Le proposte A, B e C devono essere immagini distinte");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO thumbnail_drafts (id, video_id, channel_id, status, option_a, option_b, option_c, created_at)
                VALUES (:id, :video, :channel, 'READY', :a, :b, :c, :created)
                """).param("id", id).param("video", videoId).param("channel", video.channelId())
                .param("a", encoded.get(0)).param("b", encoded.get(1)).param("c", encoded.get(2))
                .param("created", OffsetDateTime.now(clock)).update();
        return new DraftPreview(id, row(video), List.of("A", "B", "C"), "CONFERMO COPERTINA " + id + " <A|B|C>");
    }

    public synchronized Applied apply(String id, String choice, String confirmation) {
        if (!List.of("A", "B", "C").contains(choice) || !("CONFERMO COPERTINA " + id + " " + choice).equals(confirmation)) {
            throw new IllegalArgumentException("Per caricare, conferma esplicitamente la singola proposta");
        }
        String channel = tokens.requireCredential().getChannelId();
        Draft draft = jdbc.sql("""
                SELECT video_id, status, choice,
                       CASE :choice WHEN 'A' THEN option_a WHEN 'B' THEN option_b ELSE option_c END AS selected
                FROM thumbnail_drafts WHERE id = :id AND channel_id = :channel
                """).param("choice", choice).param("id", id).param("channel", channel)
                .query((rs, index) -> new Draft(rs.getString("video_id"), rs.getString("status"),
                        rs.getString("choice"), rs.getString("selected"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Proposta di copertina non trovata per il canale"));

        if ("APPLIED".equals(draft.status())) {
            if (!choice.equals(draft.choice())) throw new IllegalStateException("Una copertina è già stata applicata");
            return new Applied(id, draft.videoId(), choice, "ALREADY_APPLIED");
        }
        if (!"READY".equals(draft.status())) {
            throw new IllegalStateException("Esito da verificare su YouTube prima di un nuovo tentativo");
        }
        requireMissing(requireLongVideo(draft.videoId()));
        tokens.requireUploadScope();
        byte[] image = Base64.getDecoder().decode(draft.base64());
        int claimed = jdbc.sql("""
                UPDATE thumbnail_drafts SET status = 'APPLYING', choice = :choice
                WHERE id = :id AND channel_id = :channel AND status = 'READY'
                """).param("choice", choice).param("id", id).param("channel", channel).update();
        if (claimed != 1) throw new IllegalStateException("La proposta è già in elaborazione");

        try {
            youtube.setThumbnail(draft.videoId(), image);
        } catch (GoogleApiException ex) {
            String state = switch (ex.getStatusCode()) {
                case 400, 401, 403, 404, 429 -> "READY";
                default -> "UNKNOWN";
            };
            jdbc.sql("UPDATE thumbnail_drafts SET status = :state WHERE id = :id")
                    .param("state", state).param("id", id).update();
            if ("READY".equals(state)) throw ex;
            throw uncertain();
        } catch (RuntimeException ex) {
            jdbc.sql("UPDATE thumbnail_drafts SET status = 'UNKNOWN' WHERE id = :id").param("id", id).update();
            throw uncertain();
        }

        jdbc.sql("""
                UPDATE thumbnail_drafts SET status = 'APPLIED', option_a = NULL, option_b = NULL, option_c = NULL
                WHERE id = :id
                """).param("id", id).update();
        return new Applied(id, draft.videoId(), choice, "APPLIED");
    }

    private static IllegalStateException uncertain() {
        return new IllegalStateException("Esito del caricamento incerto. Controlla la miniatura su YouTube prima di riprovare");
    }

    private VideoMetadata requireLongVideo(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Fornisci un ID video YouTube valido");
        }
        String channel = tokens.requireCredential().getChannelId();
        VideoMetadata video = videos.getOwnedVideoForThumbnailReview(videoId);
        if (video == null || !channel.equals(video.channelId()) || !isLongVideo(video)) {
            throw new IllegalArgumentException("Serve un video di formato lungo del canale collegato");
        }
        return video;
    }

    private boolean isLongVideo(VideoMetadata video) {
        if (!video.available()) return false;
        var classification = classifier.classify(video);
        if ("MANUAL_OVERRIDE".equals(classification.detection())) return classification.type() == ContentType.VIDEO;
        if (video.durationSeconds() > 180) return true;
        Double ratio = video.displayAspectRatio();
        if (video.durationSeconds() <= 0) return false;
        if (ratio == null) return classification.type() == ContentType.VIDEO;
        if (ratio > 1.0) return true;
        if (video.publishedAt() != null && video.durationSeconds() > 60
                && video.publishedAt().atZone(YOUTUBE_ZONE).toLocalDate().isBefore(THREE_MINUTE_SHORTS)) return true;
        return false;
    }

    private static void requireMissing(VideoMetadata video) {
        if (Boolean.TRUE.equals(video.hasCustomThumbnail())) {
            throw new IllegalStateException("Il video ha già una copertina personalizzata");
        }
        if (video.hasCustomThumbnail() == null) {
            throw new IllegalStateException("YouTube non indica lo stato della miniatura. Verifica questo video in Studio");
        }
    }

    private static Row row(VideoMetadata video) {
        String status = video.hasCustomThumbnail() == null ? "UNKNOWN"
                : video.hasCustomThumbnail() ? "CUSTOM" : "MISSING";
        return new Row(video.videoId(), video.title(), video.youtubeUrl(), video.thumbnailUrl(), status,
                video.scheduledPublishAt() == null ? null : video.scheduledPublishAt().toString(),
                video.privacyStatus());
    }

    private record Draft(String videoId, String status, String choice, String base64) {}
    public record Row(String videoId, String title, String youtubeUrl, String thumbnailUrl, String thumbnailStatus,
                      String scheduledPublishAt, String visibility) {}
    public record Audit(int longVideos, int missing, int unknown, int custom, int page, boolean hasNext, List<Row> videos) {}
    public record Context(Row video, String description, YouTubeThumbnailClient.Caption caption, String style) {}
    public record DraftPreview(String draftId, Row video, List<String> choices, String confirmation) {}
    public record Applied(String draftId, String videoId, String choice, String status) {
        public String youtubeUrl() { return "https://www.youtube.com/watch?v=" + videoId; }
    }
}
