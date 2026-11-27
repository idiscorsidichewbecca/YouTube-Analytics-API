package it.chewbecca.youtubeanalytics.thumbnail;

import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService.ChannelDisconnected;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
public class CaptionExcerptCache {
    // Logical stored payload cap (including Base64), not a bound on the H2 file.
    static final long MAX_PAYLOAD_BYTES = 50L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CaptionExcerptCache(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public synchronized Optional<YouTubeThumbnailClient.Caption> find(String channel, String video) {
        return jdbc.query("""
                SELECT state, language, caption_source, payload, truncated
                FROM youtube_caption_excerpt WHERE channel_id=? AND video_id=? AND expires_at>?
                """, (rs, n) -> new YouTubeThumbnailClient.Caption(rs.getString("state"),
                rs.getString("language"), rs.getString("caption_source"),
                decode(rs.getString("payload")), rs.getBoolean("truncated")), channel, video, now())
                .stream().findFirst();
    }

    public synchronized void save(String channel, String video, YouTubeThumbnailClient.Caption caption) {
        if (!"AVAILABLE".equals(caption.state()) && !"NOT_AVAILABLE".equals(caption.state())) return;
        String payload = encode(caption.webVtt());
        int bytes = payload == null ? 0 : payload.length();
        var now = now();
        var expires = now.plus("AVAILABLE".equals(caption.state()) ? Duration.ofDays(28) : Duration.ofHours(6));
        expire();
        // Replace a stale entry and bound total payload, even for a large channel.
        jdbc.update("DELETE FROM youtube_caption_excerpt WHERE channel_id=? AND video_id=?", channel, video);
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(payload_bytes),0) FROM youtube_caption_excerpt", Long.class);
        long total = used == null ? 0 : used;
        while (total + bytes > MAX_PAYLOAD_BYTES) {
            var oldest = jdbc.query("""
                    SELECT channel_id,video_id,payload_bytes FROM youtube_caption_excerpt
                    ORDER BY fetched_at,channel_id,video_id FETCH FIRST 1 ROW ONLY
                    """, (rs, n) -> new Entry(rs.getString(1), rs.getString(2), rs.getInt(3)));
            if (oldest.isEmpty()) return;
            var entry = oldest.getFirst();
            jdbc.update("DELETE FROM youtube_caption_excerpt WHERE channel_id=? AND video_id=?", entry.channel(), entry.video());
            total -= entry.bytes();
        }
        jdbc.update("""
                INSERT INTO youtube_caption_excerpt
                (channel_id,video_id,state,language,caption_source,payload,payload_bytes,truncated,fetched_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, channel,video,caption.state(),caption.language(),caption.source(),payload,bytes,
                caption.truncated(),now,expires);
    }

    @Scheduled(cron="0 10 4 * * *", zone="Europe/Rome")
    public synchronized void expire() {
        jdbc.update("DELETE FROM youtube_caption_excerpt WHERE expires_at<=?", now());
    }

    @EventListener
    public synchronized void disconnect(ChannelDisconnected event) {
        jdbc.update("DELETE FROM youtube_caption_excerpt WHERE channel_id=?", event.channelId());
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static String encode(String text) {
        if (text == null) return null;
        try {
            var buffer = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(buffer)) {
                gzip.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("Caption cache compression failed", e); }
    }

    private static String decode(String payload) {
        if (payload == null) return null;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
            byte[] bytes = gzip.readNBytes(CaptionExcerpt.MAX_CHARS * 4 + 1);
            if (bytes.length > CaptionExcerpt.MAX_CHARS * 4) throw new IOException("Caption cache payload too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Caption cache payload invalid", e);
        }
    }

    private record Entry(String channel, String video, int bytes) {}
}
