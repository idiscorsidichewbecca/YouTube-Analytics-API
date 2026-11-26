package it.chewbecca.youtubeanalytics.instagram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;

@RestController
@ConditionalOnProperty(name = {"app.instagram.enabled", "app.instagram.reels-enabled"}, havingValue = "true")
public class InstagramReelMediaEndpoint {
    private final JdbcTemplate db;
    private final ReelAssets assets;
    private final Clock clock;

    public InstagramReelMediaEndpoint(JdbcTemplate db, ReelAssets assets, Clock clock) {
        this.db = db;
        this.assets = assets;
        this.clock = clock;
    }

    @GetMapping("/instagram-media/reels/{key}/{asset}")
    public ResponseEntity<Resource> media(@PathVariable String key, @PathVariable String asset) {
        if (!key.matches("[a-f0-9]{64}") || (!ReelAssets.VIDEO.equals(asset) && !ReelAssets.COVER.equals(asset))) {
            return ResponseEntity.notFound().build();
        }
        Integer permitted = db.queryForObject("""
                SELECT COUNT(*) FROM ig_reel_schedule WHERE asset_key=?
                AND state IN ('PREPARING','PROCESSING','PUBLISHING') AND publish_at>=?
                """, Integer.class, key, OffsetDateTime.now(clock).minusHours(1));
        if (permitted == null || permitted != 1) return ResponseEntity.notFound().build();
        var resource = assets.read(key, asset);
        if (!resource.isReadable()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(ReelAssets.COVER.equals(asset) ? MediaType.IMAGE_JPEG : MediaType.valueOf("video/mp4"))
                .body(resource);
    }
}
