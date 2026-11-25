package it.chewbecca.youtubeanalytics.performance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Repository
public class ReachRepository {
    private final JdbcTemplate jdbc;

    public ReachRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isCurrent(String channel, LocalDate day, Instant created, String reportId) {
        return jdbc.query("""
                select report_created,report_id from advisor_reach_day where channel_id=? and report_day=?
                """, (rs, row) -> {
            var previous = rs.getObject(1, OffsetDateTime.class).toInstant();
            return previous.isAfter(created) || (previous.equals(created) && rs.getString(2).compareTo(reportId) >= 0);
        }, channel, day).stream().anyMatch(Boolean::booleanValue);
    }

    @Transactional
    public void replace(String channel, LocalDate day, Instant created, String report, Instant imported,
                        List<ReachCsv.VideoDay> rows) {
        if (isCurrent(channel, day, created, report)) return;
        jdbc.update("delete from advisor_reach_day where channel_id=? and report_day=?", channel, day);
        jdbc.update("insert into advisor_reach_day values (?,?,?,?,?,?)", channel, day, report, utc(created), utc(imported), utc(imported));
        jdbc.batchUpdate("insert into advisor_video_reach values (?,?,?,?,?)", rows, 250, (statement, row) -> {
            statement.setString(1, channel);
            statement.setObject(2, day);
            statement.setString(3, row.videoId());
            statement.setLong(4, row.impressions());
            statement.setObject(5, row.ctr());
        });
    }

    public Reach reach(String channel, String video, LocalDate from, LocalDate to) {
        var points = jdbc.query("""
                select d.report_day,r.impressions,r.ctr,d.report_created,d.imported_at
                from advisor_reach_day d left join advisor_video_reach r
                on d.channel_id=r.channel_id and d.report_day=r.report_day and r.video_id=?
                where d.channel_id=? and d.report_day between ? and ? order by d.report_day
                """, (rs, n) -> new Day(rs.getObject(1, LocalDate.class), rs.getObject(2, Long.class),
                rs.getObject(3, Double.class), rs.getObject(4, OffsetDateTime.class),
                rs.getObject(5, OffsetDateTime.class)), video, channel, from, to);
        long validDays = points.stream().filter(day -> day.impressions() != null).count();
        long impressions = points.stream().filter(day -> day.impressions() != null).mapToLong(Day::impressions).sum();
        var ctrDays = points.stream().filter(day -> day.ctr() != null && day.impressions() != null).toList();
        long ctrImpressions = ctrDays.stream().mapToLong(Day::impressions).sum();
        Double ctr = ctrImpressions > 0
                ? ctrDays.stream().mapToDouble(day -> day.ctr() * day.impressions()).sum() / ctrImpressions : null;
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        String availability = validDays == 0 ? "UNAVAILABLE" : validDays == days ? "AVAILABLE" : "PARTIAL";
        String ctrAvailability = ctr == null ? "UNAVAILABLE"
                : validDays == days && ctrImpressions == impressions ? "AVAILABLE" : "PARTIAL";
        return new Reach(from, to, "YouTube Reporting API/channel_reach_basic_a1", availability, ctrAvailability,
                validDays == 0 ? null : impressions, ctr, ctrImpressions, points.size(), validDays, days, points);
    }

    @Transactional
    public void removeMissingVideos(String channel, Set<String> existing) {
        var videoIds = jdbc.queryForList("select distinct video_id from advisor_video_reach where channel_id=?",
                String.class, channel);
        for (var id : videoIds) {
            if (!existing.contains(id)) jdbc.update("delete from advisor_video_reach where channel_id=? and video_id=?",
                    channel, id);
        }
    }

    public void verified(String channel, Instant now) {
        jdbc.update("update advisor_reach_day set verified_at=? where channel_id=?", utc(now), channel);
    }

    public void expire(Instant before) {
        jdbc.update("delete from advisor_reach_day where verified_at < ?", utc(before));
    }

    static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    public record Day(LocalDate date, Long impressions, Double ctr, OffsetDateTime upstreamCreatedAt,
                      OffsetDateTime importedAt) {}
    public record Reach(LocalDate from, LocalDate to, String source, String availability, String ctrAvailability,
                        Long impressions, Double ctr, long impressionsWithCtr, int reportDays, long videoDays,
                        long requestedDays, List<Day> daily) {}
}
