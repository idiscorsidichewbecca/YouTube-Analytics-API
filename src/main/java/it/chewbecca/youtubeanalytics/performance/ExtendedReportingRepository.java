package it.chewbecca.youtubeanalytics.performance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static it.chewbecca.youtubeanalytics.performance.ReachRepository.utc;

@Repository
public class ExtendedReportingRepository {
    static final String REACH = "channel_reach_combined_a1";
    static final String SUBTITLES = "channel_subtitles_a3";
    private final JdbcTemplate jdbc;

    public ExtendedReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean isCurrent(String channel, String type, YouTubeReportingClient.Report report) {
        return jdbc.query("""
                select report_created, report_id from advisor_extended_day
                where channel_id=? and report_type=? and report_day=?
                """, (rs, n) -> {
            var stored = rs.getObject(1, OffsetDateTime.class).toInstant();
            return stored.isAfter(report.created()) || stored.equals(report.created())
                    && rs.getString(2).compareTo(report.id()) >= 0;
        }, channel, type, report.day()).stream().anyMatch(Boolean::booleanValue);
    }

    @Transactional
    public void replaceReach(String channel, YouTubeReportingClient.Report report, Instant now,
                             List<ExtendedReportingCsv.ReachRow> rows) {
        if (!replaceDay(channel, REACH, report, now)) return;
        jdbc.batchUpdate("insert into advisor_segmented_reach values (?,?,?,?,?,?,?,?,?,?)", rows, 250, (ps, row) -> {
            ps.setString(1, channel);
            ps.setString(2, REACH);
            ps.setObject(3, report.day());
            ps.setString(4, row.video());
            ps.setString(5, row.source());
            ps.setString(6, row.detail());
            ps.setString(7, row.device());
            ps.setString(8, row.os());
            ps.setLong(9, row.impressions());
            ps.setObject(10, row.ctr());
        });
    }

    @Transactional
    public void replaceSubtitles(String channel, YouTubeReportingClient.Report report, Instant now,
                                 List<ExtendedReportingCsv.SubtitleRow> rows) {
        if (!replaceDay(channel, SUBTITLES, report, now)) return;
        jdbc.batchUpdate("insert into advisor_subtitles values (?,?,?,?,?,?,?,?,?,?,?)", rows, 250, (ps, row) -> {
            ps.setString(1, channel);
            ps.setString(2, SUBTITLES);
            ps.setObject(3, report.day());
            ps.setString(4, row.video());
            ps.setString(5, row.language());
            ps.setString(6, row.autoTranslated());
            ps.setObject(7, row.engagedViews());
            ps.setObject(8, row.views());
            ps.setObject(9, row.watchMinutes());
            ps.setObject(10, row.premiumViews());
            ps.setObject(11, row.premiumWatchMinutes());
        });
    }

    private boolean replaceDay(String channel, String type, YouTubeReportingClient.Report report, Instant now) {
        if (isCurrent(channel, type, report)) return false;
        jdbc.update("delete from advisor_extended_day where channel_id=? and report_type=? and report_day=?",
                channel, type, report.day());
        jdbc.update("insert into advisor_extended_day values (?,?,?,?,?,?,?)",
                channel, type, report.day(), report.id(), utc(report.created()), utc(now), utc(now));
        return true;
    }

    @Transactional
    public void verify(String channel, String type, Set<String> existing, Instant now) {
        String table = switch (type) {
            case REACH -> "advisor_segmented_reach";
            case SUBTITLES -> "advisor_subtitles";
            default -> throw new IllegalArgumentException("Tipo report non valido");
        };
        for (var id : jdbc.queryForList("select distinct video_id from " + table + " where channel_id=?",
                String.class, channel)) {
            if (!existing.contains(id)) jdbc.update("delete from " + table + " where channel_id=? and video_id=?", channel, id);
        }
        jdbc.update("update advisor_extended_day set verified_at=? where channel_id=? and report_type=?",
                utc(now), channel, type);
    }

    public Breakdown<ReachSegment> reach(String channel, String video, LocalDate from, LocalDate to, ReachGroup group) {
        String source = group.sourceContext == null ? "cast(null as varchar)" : group.sourceContext;
        String grouping = group.sourceContext == null ? group.dimension : group.dimension + "," + group.sourceContext;
        var segments = jdbc.query("""
                select %s as segment, %s as source_type_label, sum(impressions) as impressions,
                       sum(case when ctr is not null then impressions else 0 end) as measured,
                       sum(ctr * impressions) as weighted
                from advisor_segmented_reach where channel_id=? and report_day between ? and ? %s
                group by %s order by impressions desc, segment, source_type_label limit 26
                """.formatted(group.dimension, source, filterVideo(video), grouping), (rs, row) -> {
            long measured = rs.getLong("measured");
            return new ReachSegment(nullableLabel(rs.getString("segment")), nullableLabel(rs.getString("source_type_label")),
                    rs.getLong("impressions"), measured, measured == 0 ? null : rs.getDouble("weighted") / measured);
        }, arguments(channel, from, to, video));
        return breakdown(channel, REACH, video, from, to, segments);
    }

    public Breakdown<SubtitleSegment> subtitles(String channel, String video, LocalDate from, LocalDate to) {
        var segments = jdbc.query("""
                select subtitle_language, auto_translated, sum(engaged_views), sum(views), sum(watch_minutes),
                       sum(premium_views), sum(premium_watch_minutes)
                from advisor_subtitles where channel_id=? and report_day between ? and ? %s
                group by subtitle_language, auto_translated
                order by sum(views) desc nulls last, subtitle_language, auto_translated limit 26
                """.formatted(filterVideo(video)), (rs, row) -> new SubtitleSegment(nullableLabel(rs.getString(1)),
                nullableLabel(rs.getString(2)), nullableLong(rs, 3), nullableLong(rs, 4),
                rs.getObject(5, Double.class), nullableLong(rs, 6), rs.getObject(7, Double.class)),
                arguments(channel, from, to, video));
        return breakdown(channel, SUBTITLES, video, from, to, segments);
    }

    private <T> Breakdown<T> breakdown(String channel, String type, String video, LocalDate from, LocalDate to,
                                       List<T> rows) {
        var reports = jdbc.query("""
                select report_day, report_created, imported_at from advisor_extended_day
                where channel_id=? and report_type=? and report_day between ? and ? order by report_day
                """, (rs, i) -> new ReportDay(rs.getObject(1, LocalDate.class), rs.getObject(2, OffsetDateTime.class),
                rs.getObject(3, OffsetDateTime.class)), channel, type, from, to);
        String table = REACH.equals(type) ? "advisor_segmented_reach" : "advisor_subtitles";
        Long observed = jdbc.queryForObject("select count(distinct report_day) from " + table
                + " where channel_id=? and report_day between ? and ? " + filterVideo(video), Long.class,
                arguments(channel, from, to, video));
        long requested = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        String status = rows.isEmpty() ? reports.isEmpty() ? "NO_REPORTS" : "NO_ROWS"
                : observed == requested ? "AVAILABLE" : "PARTIAL";
        return new Breakdown<>(type, status, requested, reports.size(), observed, rows.size() > 25,
                rows.size() > 25 ? List.copyOf(rows.subList(0, 25)) : rows, reports);
    }

    private static String filterVideo(String video) {
        return video == null ? "" : " and video_id=?";
    }

    private static Object[] arguments(String channel, LocalDate from, LocalDate to, String video) {
        return video == null ? new Object[]{channel, from, to} : new Object[]{channel, from, to, video};
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String nullableLabel(String value) {
        return value == null || value.isBlank() || "NULL".equalsIgnoreCase(value) ? null : value;
    }

    public enum ReachGroup {
        SOURCE("source_type", null), SOURCE_DETAIL("source_detail", "source_type"),
        DEVICE("device", null), OS("operating_system", null);

        private final String dimension;
        private final String sourceContext;

        ReachGroup(String dimension, String sourceContext) {
            this.dimension = dimension;
            this.sourceContext = sourceContext;
        }
    }

    public record ReachSegment(String label, String sourceType, long impressions, long impressionsWithCtr, Double ctr) {}
    public record SubtitleSegment(String language, String autoTranslated, Long engagedViews, Long views,
                                  Double watchTimeMinutes, Long premiumViews, Double premiumWatchTimeMinutes) {}
    public record ReportDay(LocalDate date, OffsetDateTime upstreamCreatedAt, OffsetDateTime importedAt) {}
    public record Breakdown<T>(String reportType, String availability, long requestedDays, long reportDays,
                               long daysWithRows, boolean truncated, List<T> segments, List<ReportDay> reports) {}
}
