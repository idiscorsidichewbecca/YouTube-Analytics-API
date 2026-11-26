package it.chewbecca.youtubeanalytics.instagram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = {"app.instagram.enabled", "app.instagram.reels-enabled"}, havingValue = "true")
public class InstagramReelSchedule {
    static final RowMapper<Job> JOB = (rs, i) -> new Job(rs.getString("id"), rs.getString("account_id"),
            rs.getString("asset_key"), rs.getString("title"), rs.getString("caption"),
            rs.getObject("publish_at", OffsetDateTime.class), rs.getString("state"),
            rs.getString("container_id"), rs.getString("media_id"), rs.getString("error_code"), rs.getInt("attempts"));
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final InstagramGraphClient graph;
    private final ReelAssets assets;
    private final Clock clock;
    private final boolean live;

    public InstagramReelSchedule(JdbcTemplate db, InstagramGraphClient graph, ReelAssets assets, Clock clock,
                                @Value("${app.instagram.reels-live-enabled}") boolean live) {
        this.db = db;
        this.graph = graph;
        this.assets = assets;
        this.clock = clock;
        this.live = live;
    }

    record Job(String id, String account, String key, String title, String caption, OffsetDateTime at,
               String state, String container, String media, String error, int attempts) {
        Status status() {
            return new Status(id, title, caption, at.atZoneSameInstant(ReelSlotPlanner.ROME).toOffsetDateTime(), state, media, error);
        }
    }

    public record Status(String id, String title, String caption, OffsetDateTime publishAt, String state,
                         String instagramMediaId, String diagnostic) {}
    public record Receipt(Status reel, String evidence, Integer comparableReels, Integer dayAndHourSamples) {}
    public record ScheduledReel(Receipt receipt, byte[] cover) {}

    public String requireSchedulingPermission() {
        if (!live) throw new IllegalStateException("La pubblicazione automatica dei Reel è disabilitata su Render");
        return graph.requirePublishingConnection().accountId();
    }

    public ScheduledReel schedule(Path video, String title, String description, String headline,
                                 double frameSeconds) throws IOException {
        String account = requireSchedulingPermission();
        title = text(title, 120, "Titolo");
        description = text(description, 2080, "Descrizione");
        headline = text(headline, 42, "Testo copertina");
        if (!Double.isFinite(frameSeconds) || frameSeconds < 0) {
            throw new IllegalArgumentException("Il secondo per la copertina deve essere positivo o zero");
        }
        String sourceHash = ReelAssets.sourceHash(video);
        var existing = existing(account, sourceHash);
        if (existing != null) return duplicate(existing);

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ReelSlotPlanner.ROME);
        var occupied = new HashSet<>(db.query("SELECT slot_date FROM ig_reel_schedule WHERE account_id=? AND slot_date>=?",
                (rs, i) -> rs.getObject(1, LocalDate.class), account, now.toLocalDate()));
        List<ReelSlotPlanner.Observation> history;
        String note = "";
        try {
            history = ReelSlotPlanner.observations(graph.recentMedia(account), now);
        } catch (RuntimeException ex) {
            history = List.of();
            note = "; storico Instagram non raggiungibile";
        }
        List<ReelSlotPlanner.Slot> options = ReelSlotPlanner.rank(now, occupied, history);
        if (options.isEmpty()) throw new IllegalStateException("Nessun giorno libero per un Reel nelle prossime tre settimane");
        String key = HexFormat.of().formatHex(RANDOM.generateSeed(32));
        assets.store(key, video, headline, frameSeconds);
        boolean saved = false;
        try {
            byte[] cover = assets.preview(key);
            String id = UUID.randomUUID().toString();
            String caption = title + "\n\n" + description;
            var tried = new HashSet<LocalDate>();
            for (var option : options) {
                OffsetDateTime at = option.at();
                if (!tried.add(at.toLocalDate()) || !at.isAfter(OffsetDateTime.now(clock).plusMinutes(5))) continue;
                try {
                    db.update("""
                            INSERT INTO ig_reel_schedule
                            (id,account_id,source_hash,asset_key,title,caption,publish_at,slot_date,created_at,next_attempt_at,state)
                            VALUES (?,?,?,?,?,?,?,?,?,?,'SCHEDULED')
                            """, id, account, sourceHash, key, title, caption, at, at.toLocalDate(),
                            OffsetDateTime.now(clock), at.minusMinutes(5));
                    saved = true;
                    return new ScheduledReel(new Receipt(new Status(id, title, caption, at, "SCHEDULED", null, null),
                            option.evidence() + note, option.comparableReels(), option.matchingDayAndHour()), cover);
                } catch (DuplicateKeyException ex) {
                    existing = existing(account, sourceHash);
                    if (existing != null) return duplicate(existing);
                }
            }
            throw new IllegalStateException("I giorni disponibili sono stati occupati. Riprova per aggiornare il piano");
        } finally {
            if (!saved) assets.remove(key);
        }
    }

    public List<Status> status(String id) {
        String account = graph.account();
        if (id == null || id.isBlank()) {
            return db.query("""
                    SELECT * FROM ig_reel_schedule WHERE account_id=? ORDER BY created_at DESC LIMIT 30
                    """, JOB, account).stream().map(Job::status).toList();
        }
        List<Status> rows = db.query("SELECT * FROM ig_reel_schedule WHERE account_id=? AND id=?", JOB, account, id)
                .stream().map(Job::status).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Programmazione Reel non trovata");
        return rows;
    }

    private Job existing(String account, String hash) {
        return db.query("SELECT * FROM ig_reel_schedule WHERE account_id=? AND source_hash=?", JOB, account, hash)
                .stream().findFirst().orElse(null);
    }

    private ScheduledReel duplicate(Job job) throws IOException {
        byte[] preview = assets.read(job.key(), ReelAssets.COVER).exists() ? assets.preview(job.key()) : null;
        return new ScheduledReel(new Receipt(job.status(), "Questo MP4 è già registrato; stato esistente", null, null), preview);
    }

    private static String text(String input, int max, String name) {
        if (input == null || input.isBlank() || input.strip().length() > max) {
            throw new IllegalArgumentException(name + " obbligatorio, massimo " + max + " caratteri");
        }
        return input.strip();
    }
}
