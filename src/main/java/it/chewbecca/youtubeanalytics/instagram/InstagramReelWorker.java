package it.chewbecca.youtubeanalytics.instagram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name = {"app.instagram.enabled", "app.instagram.reels-enabled"}, havingValue = "true")
class InstagramReelWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InstagramReelWorker.class);
    private final JdbcTemplate db;
    private final InstagramGraphClient graph;
    private final ReelAssets assets;
    private final Clock clock;
    private final String url;
    private final boolean live;

    InstagramReelWorker(JdbcTemplate db, InstagramGraphClient graph, ReelAssets assets, Clock clock,
                        @Value("${app.instagram.public-url}") String url,
                        @Value("${app.instagram.reels-live-enabled}") boolean live) {
        this.db = db;
        this.graph = graph;
        this.assets = assets;
        this.clock = clock;
        var publicUrl = URI.create(url);
        if (!"https".equals(publicUrl.getScheme()) || publicUrl.getHost() == null || publicUrl.getUserInfo() != null
                || publicUrl.getQuery() != null || publicUrl.getFragment() != null
                || !(publicUrl.getPath().isEmpty() || "/".equals(publicUrl.getPath()))) {
            throw new IllegalArgumentException("I Reel richiedono l'URL HTTPS pubblico di Chewbecca Studio");
        }
        this.url = url.replaceAll("/+$", "") + "/instagram-media/reels/";
        this.live = live;
    }

    @Scheduled(fixedDelay = 30_000)
    void advance() {
        if (!live) return;
        OffsetDateTime now = now();
        db.update("""
                UPDATE ig_reel_schedule SET state='UNKNOWN',error_code='PUBLISH_RESULT_UNCONFIRMED',next_attempt_at=?
                WHERE state='PUBLISHING' AND next_attempt_at<=?
                """, now, now);
        db.update("""
                UPDATE ig_reel_schedule SET state='MISSED',error_code='PUBLICATION_WINDOW_EXPIRED',slot_date=NULL
                WHERE state IN ('SCHEDULED','PREPARING','PROCESSING') AND publish_at<?
                """, now.minusMinutes(45));
        var jobs = db.query("""
                SELECT * FROM ig_reel_schedule WHERE state IN ('SCHEDULED','PREPARING','PROCESSING','UNKNOWN')
                AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT 6
                """, InstagramReelSchedule.JOB, now);
        for (var job : jobs) {
            try {
                switch (job.state()) {
                    case "SCHEDULED", "PREPARING" -> prepare(job);
                    case "PROCESSING" -> advanceContainer(job);
                    case "UNKNOWN" -> reconcile(job);
                    default -> throw new IllegalStateException("Stato Reel non previsto");
                }
            } catch (RuntimeException ex) {
                LOG.warn("Instagram Reel worker job={} state={} exception={}", job.id(), job.state(), ex.getClass().getSimpleName());
            }
        }
    }

    private void prepare(InstagramReelSchedule.Job job) {
        if (db.update("""
                UPDATE ig_reel_schedule SET state='PREPARING',next_attempt_at=?,attempts=attempts+1
                WHERE id=? AND state IN ('SCHEDULED','PREPARING') AND next_attempt_at<=?
                """, now().plusMinutes(5), job.id(), now()) != 1) return;
        try {
            graph.requirePublishingConnection(job.account());
            String source = url + job.key() + "/";
            String container = graph.createReel(job.account(), source + ReelAssets.VIDEO, source + ReelAssets.COVER, job.caption());
            db.update("""
                    UPDATE ig_reel_schedule SET state='PROCESSING',container_id=?,next_attempt_at=?,error_code=NULL
                    WHERE id=? AND state='PREPARING'
                    """, container, now().plusMinutes(1), job.id());
        } catch (RuntimeException ex) {
            String code = error(ex, "REEL_CONTAINER_CREATE_FAILED");
            if (job.attempts() >= 2) fail(job.id(), "PREPARING", code);
            else db.update("UPDATE ig_reel_schedule SET error_code=? WHERE id=? AND state='PREPARING'", code, job.id());
        }
    }

    private void advanceContainer(InstagramReelSchedule.Job job) {
        if (db.update("""
                UPDATE ig_reel_schedule SET next_attempt_at=?
                WHERE id=? AND state='PROCESSING' AND next_attempt_at<=?
                """, now().plusMinutes(1), job.id(), now()) != 1) return;
        String status = graph.containerStatus(job.account(), job.container());
        switch (status) {
            case "FINISHED" -> {
                if (!now().isBefore(job.at())) publish(job);
            }
            case "PUBLISHED" -> markPublished(job.id(), null, "PROCESSING");
            case "ERROR", "EXPIRED" -> fail(job.id(), "PROCESSING", "REEL_CONTAINER_" + status);
            default -> { /* Continue polling at the next worker tick. */ }
        }
    }

    private void publish(InstagramReelSchedule.Job job) {
        if (db.update("""
                UPDATE ig_reel_schedule SET state='PUBLISHING',next_attempt_at=?
                WHERE id=? AND state='PROCESSING'
                """, now().plusMinutes(5), job.id()) != 1) return;
        try {
            String mediaId = graph.publish(job.account(), job.container());
            markPublished(job.id(), mediaId, "PUBLISHING");
        } catch (RuntimeException ex) {
            db.update("""
                    UPDATE ig_reel_schedule SET state='UNKNOWN',error_code=?,next_attempt_at=?
                    WHERE id=? AND state='PUBLISHING'
                    """, error(ex, "REEL_PUBLISH_RESULT_UNCONFIRMED"), now().plusMinutes(1), job.id());
        }
    }

    private void reconcile(InstagramReelSchedule.Job job) {
        OffsetDateTime now = now();
        if (db.update("UPDATE ig_reel_schedule SET next_attempt_at=? WHERE id=? AND state='UNKNOWN' AND next_attempt_at<=?",
                now.plusMinutes(15), job.id(), now) != 1) return;
        if (now.isAfter(job.at().plusHours(25))) {
            db.update("""
                    UPDATE ig_reel_schedule SET next_attempt_at=?, error_code='VERIFY_INSTAGRAM_MANUALLY'
                    WHERE id=? AND state='UNKNOWN'
                    """, now.plusYears(100), job.id());
            return;
        }
        if ("PUBLISHED".equals(graph.containerStatus(job.account(), job.container()))) {
            markPublished(job.id(), null, "UNKNOWN");
        }
    }

    private void markPublished(String id, String media, String previous) {
        db.update("UPDATE ig_reel_schedule SET state='PUBLISHED',media_id=?,error_code=NULL WHERE id=? AND state=?",
                media, id, previous);
    }

    private void fail(String id, String previous, String code) {
        db.update("UPDATE ig_reel_schedule SET state='FAILED',error_code=?,slot_date=NULL WHERE id=? AND state=?",
                code, id, previous);
    }

    @Scheduled(fixedDelay = 900_000)
    void cleanup() {
        var jobs = db.query("""
                SELECT * FROM ig_reel_schedule WHERE assets_removed=FALSE
                AND (state IN ('PUBLISHED','FAILED','MISSED') OR (state='UNKNOWN' AND publish_at<?))
                LIMIT 30
                """, InstagramReelSchedule.JOB, now().minusHours(25));
        for (var job : jobs) {
            try {
                assets.remove(job.key());
                db.update("UPDATE ig_reel_schedule SET assets_removed=TRUE WHERE id=?", job.id());
            } catch (IOException ex) {
                LOG.warn("Instagram Reel media cleanup failed job={}", job.id());
            }
        }
        try {
            assets.cleanOrphans(clock.instant(), key -> Boolean.TRUE.equals(db.queryForObject(
                    "SELECT COUNT(*)>0 FROM ig_reel_schedule WHERE asset_key=?", Boolean.class, key)));
        } catch (IOException ex) {
            LOG.warn("Instagram Reel orphan cleanup failed");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String error(RuntimeException ex, String fallback) {
        if (ex instanceof InstagramGraphClient.GraphFailure && ex.getMessage() != null
                && ex.getMessage().matches("[A-Z0-9_]{1,80}")) return ex.getMessage();
        return fallback;
    }
}
