package it.chewbecca.youtubeanalytics.instagram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@ConditionalOnProperty(name = {"app.instagram.enabled", "app.instagram.reels-enabled"}, havingValue = "true")
public class InstagramReelBatchService {
    private final JdbcTemplate db;
    private final InstagramGraphClient graph;
    private final InstagramReelSchedule schedule;
    private final Clock clock;

    public InstagramReelBatchService(JdbcTemplate db, InstagramGraphClient graph, InstagramReelSchedule schedule, Clock clock) {
        this.db = db;
        this.graph = graph;
        this.schedule = schedule;
        this.clock = clock;
    }

    public record Entry(String filename, String title, String description, String coverHeadline,
                        double coverSecond, ReelCoverTemplate coverTemplate) {}
    public record Draft(int position, String filename, String title, String description, String coverHeadline,
                        double coverSecond, String coverTemplate, OffsetDateTime publishAt, String evidence,
                        int comparableReels, int dayAndHourSamples, String reelId) {}
    public record Batch(String batchId, String status, List<Draft> reels, String confirmationText) {}
    public record Submission(String batchId, int position, InstagramReelSchedule.Receipt receipt, byte[] cover) {}

    private static final RowMapper<Draft> DRAFT = (rs, i) -> new Draft(rs.getInt("position"), rs.getString("filename"),
            rs.getString("title"), rs.getString("description"), rs.getString("cover_headline"), rs.getDouble("cover_second"),
            rs.getString("cover_template"), rs.getObject("publish_at", OffsetDateTime.class)
                    .atZoneSameInstant(ReelSlotPlanner.ROME).toOffsetDateTime(), rs.getString("evidence"),
            rs.getInt("comparable_reels"), rs.getInt("day_hour_samples"), rs.getString("reel_id"));

    @Transactional
    public Batch prepare(List<Entry> entries, InstagramReelSchedule.DateWindow window) {
        if (entries == null || entries.isEmpty() || entries.size() > 10)
            throw new IllegalArgumentException("Fornisci da 1 a 10 Reel");
        var names = new HashSet<String>();
        for (Entry entry : entries) {
            check(entry.filename(), 512, "Nome file");
            if (!entry.filename().toLowerCase(Locale.ROOT).endsWith(".mp4") || !names.add(entry.filename()))
                throw new IllegalArgumentException("Usa nomi MP4 distinti");
            check(entry.title(), 120, "Titolo");
            check(entry.description(), 2080, "Descrizione");
            check(entry.coverHeadline(), 42, "Testo copertina");
            if (!Double.isFinite(entry.coverSecond()) || entry.coverSecond() < 0 || entry.coverTemplate() == null)
                throw new IllegalArgumentException("Configurazione copertina non valida");
        }
        String account = schedule.requireSchedulingPermission();
        // Remove only unsubmitted expired drafts; completed positions retain their receipts.
        db.update("DELETE FROM ig_reel_batch_draft WHERE account_id=? AND reel_id IS NULL AND publish_at<=?",
                account, OffsetDateTime.now(clock));
        var reserved = new HashSet<>(db.query("SELECT slot_date FROM ig_reel_batch_draft WHERE account_id=?",
                (rs, i) -> rs.getObject(1, LocalDate.class), account));
        var slots = schedule.planBatch(window, entries.size(), reserved);
        String id = UUID.randomUUID().toString();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            var slot = slots.get(i);
            db.update("""
                    INSERT INTO ig_reel_batch_draft
                    (batch_id, position, account_id, filename, title, description, cover_headline, cover_second,
                     cover_template, publish_at, slot_date, evidence, comparable_reels, day_hour_samples, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, i + 1, account, e.filename(), e.title(), e.description(), e.coverHeadline(),
                    e.coverSecond(), e.coverTemplate().name(), slot.publishAt(), slot.publishAt().toLocalDate(),
                    slot.evidence(), slot.comparableReels(), slot.dayAndHourSamples(), OffsetDateTime.now(clock));
        }
        return status(id);
    }

    public Batch status(String id) {
        String account = graph.account();
        var drafts = db.query("SELECT * FROM ig_reel_batch_draft WHERE account_id=? AND batch_id=? ORDER BY position",
                DRAFT, account, id);
        if (drafts.isEmpty()) throw new IllegalArgumentException("Batch Instagram non trovato");
        long count = drafts.stream().filter(d -> d.reelId() != null).count();
        return new Batch(id, count == drafts.size() ? "SCHEDULED" : count == 0 ? "DRAFT" : "PARTIAL",
                drafts, "CONFERMO REEL BATCH " + id);
    }

    public Draft draft(String id, int position, String confirmation) {
        if (!("CONFERMO REEL BATCH " + id).equals(confirmation))
            throw new IllegalArgumentException("Manca la conferma esplicita del batch Instagram");
        return status(id).reels().stream().filter(d -> d.position() == position).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Posizione batch Instagram non trovata"));
    }

    public Submission submit(String id, int position, String confirmation, Path video, String filename) throws IOException {
        String account = schedule.requireSchedulingPermission();
        Draft d = draft(id, position, confirmation);
        if (d.reelId() != null) return existing(id, d);
        if (filename != null && !filename.isBlank() && !d.filename().equals(filename))
            throw new IllegalArgumentException("L'allegato non corrisponde alla posizione approvata: " + d.filename());
        LocalDate day = d.publishAt().toLocalDate();
        var result = schedule.scheduleAt(video, d.title(), d.description(), d.coverHeadline(), d.coverSecond(),
                ReelCoverTemplate.fromInput(d.coverTemplate()), new InstagramReelSchedule.DateWindow(day, day), d.publishAt());
        var receipt = result.receipt();
        if (!receipt.reel().publishAt().toInstant().equals(d.publishAt().toInstant()))
            throw new IllegalStateException("Il file risulta già registrato per un orario diverso; controlla la coda");
        int updated = db.update("""
                UPDATE ig_reel_batch_draft SET reel_id=? WHERE account_id=? AND batch_id=? AND position=?
                AND (reel_id IS NULL OR reel_id=?)
                """, receipt.reel().id(), account, id, position, receipt.reel().id());
        if (updated != 1) throw new IllegalStateException("Stato batch non aggiornato; controlla la coda prima di riprovare");
        return new Submission(id, position, new InstagramReelSchedule.Receipt(receipt.reel(), d.evidence(),
                d.comparableReels(), d.dayAndHourSamples()), result.cover());
    }

    public Submission existing(String id, Draft d) {
        var actual = schedule.status(d.reelId()).getFirst();
        return new Submission(id, d.position(), new InstagramReelSchedule.Receipt(actual, d.evidence(),
                d.comparableReels(), d.dayAndHourSamples()), null);
    }

    private static void check(String text, int limit, String label) {
        if (text == null || text.isBlank() || text.length() > limit)
            throw new IllegalArgumentException(label + " obbligatorio, massimo " + limit + " caratteri");
    }
}
