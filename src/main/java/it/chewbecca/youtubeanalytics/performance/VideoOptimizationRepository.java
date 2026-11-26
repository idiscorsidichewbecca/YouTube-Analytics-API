package it.chewbecca.youtubeanalytics.performance;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static it.chewbecca.youtubeanalytics.performance.ReachRepository.utc;

@Repository
public class VideoOptimizationRepository {
    private final JdbcTemplate jdbc;

    public VideoOptimizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Draft create(Draft draft, List<byte[]> photos) {
        jdbc.update("""
                insert into advisor_optimization
                (id,channel_id,video_id,action,state,revision,rationale,original_value,created_at,draft_options)
                values (?,?,?,?,?,?,?,?,?,?)
                """, draft.id(), draft.channel(), draft.videoId(), draft.action(), draft.state(), draft.revision(),
                draft.reason(), draft.previous(), utc(draft.createdAt()), draft.options());
        for (int i = 0; i < photos.size(); i++) {
            jdbc.update("insert into advisor_optimization_image values (?,?,?)", draft.id(),
                    String.valueOf((char) ('A' + i)), Base64.getEncoder().encodeToString(photos.get(i)));
        }
        return draft;
    }

    public Draft draft(String channel, String id) {
        return jdbc.query("select * from advisor_optimization where channel_id=? and id=?", this::draft, channel, id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Proposta video non trovata"));
    }

    public List<Draft> history(String channel, String videoId) {
        return jdbc.query("""
                select * from advisor_optimization where channel_id=? and video_id=?
                order by created_at desc limit 50
                """, this::draft, channel, videoId);
    }

    public List<Draft> applied(String channel, Instant after) {
        return jdbc.query("""
                select * from advisor_optimization where channel_id=? and state='APPLIED' and applied_at>=?
                order by applied_at desc
                """, this::draft, channel, utc(after));
    }

    public List<EditorialMemory> editorialHistory(String channel, String videoId) {
        return jdbc.query("""
                select id, action, rationale, applied_value, applied_at, editorial_note, editorial_note_at
                from advisor_optimization where channel_id=? and video_id=? and state='APPLIED'
                order by applied_at desc, id limit 20
                """, (rs, row) -> new EditorialMemory(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, OffsetDateTime.class).toInstant(), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)), channel, videoId);
    }

    @Transactional
    public void saveEditorialNote(String channel, String id, String note, Instant now) {
        if (jdbc.update("""
                update advisor_optimization set editorial_note=?, editorial_note_at=?
                where channel_id=? and id=? and state='APPLIED'
                """, note, utc(now), channel, id) != 1) {
            throw new IllegalArgumentException("Intervento applicato non trovato");
        }
    }

    public record EditorialMemory(String id, String action, String reason, String appliedValue, Instant appliedAt,
                                  String editorialNote, OffsetDateTime editorialNoteAt) {}

    public Set<String> pendingOrCooling(String channel, Instant since) {
        return new HashSet<>(jdbc.queryForList("""
                select video_id from advisor_optimization where channel_id=?
                and (state in ('APPLYING','UNKNOWN') or (state='APPLIED' and applied_at>=?))
                """, String.class, channel, utc(since)));
    }

    @Transactional
    public boolean claim(Draft draft, String choice, Instant now) {
        try {
            jdbc.update("insert into advisor_optimization_claim values (?,?,?,?)", draft.channel(), draft.videoId(),
                    draft.action(), draft.id());
        } catch (DuplicateKeyException alreadyClaimed) {
            return false;
        }
        if (jdbc.update("""
                update advisor_optimization set state='APPLYING',applied_value=?,applied_at=?
                where id=? and channel_id=? and state='READY' and revision=?
                """, choice, utc(now), draft.id(), draft.channel(), draft.revision()) != 1) {
            throw new IllegalStateException("La proposta è già stata elaborata");
        }
        return true;
    }

    @Transactional
    public void finish(Draft draft, String state, String result, Instant at, String beforeMetrics, boolean release) {
        jdbc.update("""
                update advisor_optimization set state=?,applied_value=?,applied_at=?,
                before_metrics=coalesce(?,before_metrics)
                where id=? and channel_id=? and state in ('APPLYING','UNKNOWN')
                """, state, result, at == null ? null : utc(at), beforeMetrics, draft.id(), draft.channel());
        if ("APPLIED".equals(state)) {
            jdbc.update("delete from advisor_optimization_image where optimization_id=?", draft.id());
        }
        if (release) jdbc.update("delete from advisor_optimization_claim where optimization_id=?", draft.id());
    }

    public void baseline(Draft draft, String json) {
        jdbc.update("update advisor_optimization set before_metrics=? where id=? and state='APPLYING'", json, draft.id());
    }

    public byte[] image(String id, String choice) {
        return jdbc.query("select image_base64 from advisor_optimization_image where optimization_id=? and choice=?",
                (rs, n) -> Base64.getDecoder().decode(rs.getString(1)), id, choice).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Immagine registrata mancante"));
    }

    @Transactional
    public void saveEvaluation(String id, int days, Instant now, String json) {
        int updated = jdbc.update("""
                update advisor_optimization_evaluation set observed_at=?,payload=?
                where optimization_id=? and window_days=?
                """, utc(now), json, id, days);
        if (updated == 0) jdbc.update("insert into advisor_optimization_evaluation values (?,?,?,?)", id, days,
                utc(now), json);
    }

    public List<String> evaluations(String id) {
        return jdbc.queryForList("""
                select payload from advisor_optimization_evaluation where optimization_id=? order by window_days
                """, String.class, id);
    }

    @Transactional
    public void purgeExpiredData(Instant before) {
        jdbc.update("delete from advisor_optimization where created_at < ? and state in ('READY','STALE')", utc(before));
        jdbc.update("delete from advisor_optimization_evaluation where observed_at < ?", utc(before));
        jdbc.update("""
                update advisor_optimization set before_metrics=null where applied_at < ? and before_metrics is not null
                """, utc(before));
        jdbc.update("""
                delete from advisor_optimization_image where optimization_id in
                (select id from advisor_optimization where created_at < ?)
                """, utc(before));
        jdbc.update("""
                update advisor_optimization set original_value=null where original_value is not null and created_at < ?
                """, utc(before));
    }

    private Draft draft(ResultSet rs, int n) throws SQLException {
        var applied = rs.getObject("applied_at", OffsetDateTime.class);
        return new Draft(rs.getString("id"), rs.getString("channel_id"), rs.getString("video_id"),
                rs.getString("action"), rs.getString("state"), rs.getInt("revision"), rs.getString("rationale"),
                rs.getString("original_value"), rs.getString("applied_value"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                applied == null ? null : applied.toInstant(), rs.getString("before_metrics"), rs.getString("draft_options"));
    }

    public record Draft(String id, String channel, String videoId, String action, String state, int revision,
                        String reason, String previous, String applied, Instant createdAt, Instant appliedAt,
                        String beforeMetrics, String options) {}
}
