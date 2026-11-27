package it.chewbecca.youtubeanalytics.thumbnail;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService.ChannelDisconnected;

class CaptionExcerptCacheTest {
    @Test void extractsStartMiddleEndWithoutTimestamps() {
        String vtt = "WEBVTT\n\n" + IntStream.rangeClosed(1,300)
                .mapToObj(i -> "00:00:00.000 --> 00:00:01.000\n<c>Riga " + i + "</c>")
                .collect(Collectors.joining("\n\n"));
        var excerpt = CaptionExcerpt.fromVtt(vtt);
        assertThat(excerpt.text().lines().toList()).hasSize(150)
                .contains("Riga 1", "Riga 150", "Riga 300").doesNotContain("Riga 100");
        assertThat(excerpt.text()).doesNotContain("-->", "<c>", "WEBVTT");
        assertThat(excerpt.truncated()).isTrue();
        var longExcerpt = CaptionExcerpt.fromVtt(vtt.replace("Riga ", "a".repeat(500)));
        assertThat(longExcerpt.text().length()).isLessThanOrEqualTo(15000);
    }

    @Test void persistedResultsAreScopedAndExpireWithoutExtendingRetentionOnRead() {
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:db/migration/V15__caption_excerpt_cache.sql").build();
        try {
            var jdbc = new JdbcTemplate(db);
            var now = Instant.parse("2026-11-27T00:00:00Z");
            var cache = new CaptionExcerptCache(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            var caption = new YouTubeThumbnailClient.Caption("AVAILABLE","it","standard","Testo è",false);
            cache.save("channel1","abcdefghijk",caption);
            cache.save("channel1","ABCDEFGHIJK",YouTubeThumbnailClient.Caption.unavailable("NOT_AVAILABLE"));
            cache.save("channel2","abcdefghijk",caption);
            cache.save("channel1","01234567890",YouTubeThumbnailClient.Caption.unavailable("PERMISSION_OR_QUOTA"));
            var next = new CaptionExcerptCache(jdbc, Clock.fixed(now.plusSeconds(7*3600), ZoneOffset.UTC));
            assertThat(next.find("channel1","abcdefghijk")).contains(caption);
            assertThat(next.find("channel1","ABCDEFGHIJK")).isEmpty();
            assertThat(next.find("channel1","01234567890")).isEmpty();
            assertThat(next.find("unknown","abcdefghijk")).isEmpty();
            next.disconnect(new ChannelDisconnected("channel2"));
            assertThat(next.find("channel2","abcdefghijk")).isEmpty();
            var expired = new CaptionExcerptCache(jdbc, Clock.fixed(now.plusSeconds(28L*86400), ZoneOffset.UTC));
            expired.expire();
            assertThat(expired.find("channel1","abcdefghijk")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM youtube_caption_excerpt",Integer.class)).isZero();
        } finally { db.shutdown(); }
    }
}
