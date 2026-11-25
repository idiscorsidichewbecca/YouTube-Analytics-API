package it.chewbecca.youtubeanalytics.community;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CommunityReplyRepository {
    private final JdbcTemplate jdbc;

    public CommunityReplyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Batch create(String channel, Instant now, List<Item> items) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into community_reply_batch values (?,?,1,?)", id, channel, now.atOffset(ZoneOffset.UTC));
        for (var item : items) {
            jdbc.update("""
                    insert into community_reply_item
                    (batch_id,item_no,thread_id,comment_id,parent_id,video_id,video_title,author,original_text,
                    response_text,priority,reason,status) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, item.number(), item.threadId(), item.commentId(), item.parentId(), item.videoId(),
                    item.videoTitle(), item.author(), item.original(), item.reply(), item.priority(), item.reason(),
                    item.status());
        }
        return get(channel, id);
    }

    public Batch get(String channel, String id) {
        var headers = jdbc.query("select revision,created_at from community_reply_batch where channel_id=? and id=?",
                (rs, n) -> new Header(rs.getInt(1), rs.getObject(2, OffsetDateTime.class)), channel, id);
        if (headers.isEmpty()) throw new IllegalArgumentException("Batch di risposte non trovato");
        var header = headers.getFirst();
        var items = jdbc.query("select * from community_reply_item where batch_id=? order by item_no", (rs, n) ->
                new Item(rs.getInt("item_no"), rs.getString("thread_id"), rs.getString("comment_id"),
                        rs.getString("parent_id"), rs.getString("video_id"), rs.getString("video_title"),
                        rs.getString("author"), rs.getString("original_text"), rs.getString("response_text"),
                        rs.getString("priority"), rs.getString("reason"), rs.getString("status"),
                        rs.getString("youtube_reply_id")), id);
        return new Batch(id, header.revision(), header.createdAt(), items);
    }

    @Transactional
    public Batch revise(String channel, String id, int revision, List<Edit> edits) {
        if (jdbc.update("update community_reply_batch set revision=revision+1 where id=? and channel_id=? and revision=?",
                id, channel, revision) != 1) throw new IllegalStateException("Revisione superata. Rileggi l'anteprima.");
        var items = get(channel, id).items();
        for (var edit : edits) {
            var item = items.stream().filter(value -> value.number() == edit.number()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Numero di risposta inesistente"));
            if (!List.of("DRAFT", "APPROVED", "EXCLUDED", "REVIEW").contains(item.status())) {
                throw new IllegalStateException("La risposta è già in esecuzione o ha un esito registrato");
            }
            var status = switch (edit.action()) {
                case "APPROVE" -> "APPROVED";
                case "EDIT" -> "DRAFT";
                case "EXCLUDE" -> "EXCLUDED";
                default -> throw new IllegalArgumentException("Azione ammessa: APPROVE, EDIT, EXCLUDE");
            };
            String reply = edit.action().equals("EDIT") ? edit.reply() : item.reply();
            if (status.equals("APPROVED") && (item.status().equals("REVIEW") || reply == null || reply.isBlank())) {
                throw new IllegalStateException("Scrivi e mostra una proposta prima di approvarla");
            }
            if (jdbc.update("""
                    update community_reply_item set status=?, response_text=? where batch_id=? and item_no=? and status=?
                    """, status, reply, id, item.number(), item.status()) != 1) {
                throw new IllegalStateException("La risposta è cambiata. Rileggi l'anteprima.");
            }
        }
        return get(channel, id);
    }

    @Transactional
    public boolean claim(String channel, String id, int revision, Item item) {
        var batch = jdbc.queryForList("select revision from community_reply_batch where id=? and channel_id=? for update",
                Integer.class, id, channel);
        if (batch.isEmpty() || batch.getFirst() != revision) {
            throw new IllegalStateException("Revisione superata. Rileggi l'anteprima.");
        }
        try {
            jdbc.update("insert into community_reply_claim values (?,?,?,?)", channel, item.parentId(), id, item.number());
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        if (jdbc.update("update community_reply_item set status='SENDING' where batch_id=? and item_no=? and status='APPROVED'",
                id, item.number()) != 1) throw new IllegalStateException("La risposta è già stata presa in carico");
        return true;
    }

    @Transactional
    public void finish(String channel, String id, Item item, String state, String replyId, boolean release) {
        jdbc.update("""
                update community_reply_item set status=?, youtube_reply_id=?
                where batch_id=? and item_no=? and status in ('SENDING','UNKNOWN')
                """, state, replyId, id, item.number());
        if (release) jdbc.update("delete from community_reply_claim where channel_id=? and parent_id=? and batch_id=?",
                channel, item.parentId(), id);
    }

    public List<Map<String, Object>> counts(String channel) {
        return jdbc.queryForList("""
                select i.status,count(*) as total from community_reply_item i join community_reply_batch b
                on i.batch_id=b.id where b.channel_id=? group by i.status order by i.status
                """, channel);
    }

    public void expire(Instant before) {
        jdbc.update("delete from community_reply_batch where created_at < ?", before.atOffset(ZoneOffset.UTC));
    }

    public record Item(int number, String threadId, String commentId, String parentId, String videoId,
                       String videoTitle, String author, String original, String reply, String priority,
                       String reason, String status, String youtubeReplyId) {}
    public record Batch(String id, int revision, OffsetDateTime createdAt, List<Item> items) {
        public String confirmation() { return "PUBBLICA RISPOSTE " + id + " REV " + revision; }
    }
    public record Edit(int number, String action, String reply) {}
    private record Header(int revision, OffsetDateTime createdAt) {}
}
