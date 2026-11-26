package it.chewbecca.youtubeanalytics.performance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static it.chewbecca.youtubeanalytics.performance.ReachCsv.column;
import static it.chewbecca.youtubeanalytics.performance.ReachCsv.columns;
import static it.chewbecca.youtubeanalytics.performance.ReachRepository.utc;

@Repository
public class EndScreenReportingRepository {
    static final String TYPE = "channel_end_screens_a2";
    private final JdbcTemplate jdbc;

    public EndScreenReportingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static List<VideoDay> parse(Reader input, String channel, LocalDate reportDay) throws IOException {
        var reader = new BufferedReader(input);
        String header = reader.readLine();
        if (header == null) throw new IOException("CSV schermate finali senza intestazione");
        var keys = columns(header.replaceFirst("^\uFEFF", ""));
        int date = column(keys, "date");
        int owner = column(keys, "channel_id");
        int video = column(keys, "video_id");
        int type = column(keys, "end_screen_element_type");
        int impressions = column(keys, "end_screen_element_impressions");
        int clicks = column(keys, "end_screen_element_clicks");
        var sums = new HashMap<List<String>, VideoDay>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            var cells = columns(line);
            if (cells.size() != keys.size() || !reportDay.equals(LocalDate.parse(cells.get(date), DateTimeFormatter.BASIC_ISO_DATE))) {
                throw new IOException("Riga schermate finali con colonne, giorno o canale inattesi");
            }
            String id = cells.get(video);
            if (id.isBlank() || cells.get(owner).isBlank()) continue;
            if (!channel.equals(cells.get(owner))) throw new IOException("Canale CSV schermate finali inatteso");
            String element = cells.get(type);
            long impressionsCount = Long.parseLong(cells.get(impressions));
            long clicksCount = Long.parseLong(cells.get(clicks));
            if (!id.matches("[A-Za-z0-9_-]{11}") || impressionsCount < 0 || clicksCount < 0) {
                throw new IOException("Riga schermate finali con valori inattesi");
            }
            sums.merge(List.of(id, element), new VideoDay(id, element, impressionsCount, clicksCount), (first, second) ->
                    new VideoDay(id, element, Math.addExact(first.impressions, second.impressions),
                            Math.addExact(first.clicks, second.clicks)));
        }
        return List.copyOf(sums.values());
    }

    public boolean isCurrent(String channel, YouTubeReportingClient.Report report) {
        return jdbc.query("""
                select report_created,report_id,has_element_breakdown from advisor_end_screen_day where channel_id=? and report_day=?
                """, (rs, n) -> {
            var stored = rs.getObject(1, OffsetDateTime.class).toInstant();
            return stored.isAfter(report.created()) || (stored.equals(report.created())
                    && (rs.getString(2).compareTo(report.id()) > 0
                    || (rs.getString(2).equals(report.id()) && rs.getBoolean(3))));
        }, channel, report.day()).stream().anyMatch(Boolean::booleanValue);
    }

    @Transactional
    public void replace(String channel, YouTubeReportingClient.Report report, Instant now, List<VideoDay> rows) {
        if (isCurrent(channel, report)) return;
        jdbc.update("delete from advisor_end_screen_day where channel_id=? and report_day=?", channel, report.day());
        jdbc.update("insert into advisor_end_screen_day values (?,?,?,?,?,?,true)", channel, report.day(), report.id(),
                utc(report.created()), utc(now), utc(now));
        jdbc.batchUpdate("insert into advisor_end_screen_type values (?,?,?,?,?,?)", rows, 250, (stmt, row) -> {
            stmt.setString(1, channel);
            stmt.setObject(2, report.day());
            stmt.setString(3, row.videoId());
            stmt.setString(4, row.elementType());
            stmt.setLong(5, row.impressions());
            stmt.setLong(6, row.clicks());
        });
        jdbc.update("""
                insert into advisor_end_screen_metric
                select channel_id,report_day,video_id,sum(impressions),sum(clicks) from advisor_end_screen_type
                where channel_id=? and report_day=? group by channel_id,report_day,video_id
                """, channel, report.day());
    }

    @Transactional
    public void verify(String channel, Set<String> existing, Instant now) {
        var ids = jdbc.queryForList("select distinct video_id from advisor_end_screen_metric where channel_id=?",
                String.class, channel);
        for (var id : ids) {
            if (!existing.contains(id)) {
                jdbc.update("delete from advisor_end_screen_metric where channel_id=? and video_id=?", channel, id);
                jdbc.update("delete from advisor_end_screen_type where channel_id=? and video_id=?", channel, id);
            }
        }
        jdbc.update("update advisor_end_screen_day set verified_at=? where channel_id=?", utc(now), channel);
    }

    public Totals totals(String channel, String video, LocalDate from, LocalDate to) {
        String videoFilter = video == null ? "" : " and m.video_id=?";
        Object[] arguments = video == null ? new Object[]{channel, from, to} : new Object[]{video, channel, from, to};
        var rows = jdbc.query("""
                select d.report_day,d.report_created,d.imported_at,sum(m.impressions),sum(m.clicks)
                from advisor_end_screen_day d left join advisor_end_screen_metric m
                on d.channel_id=m.channel_id and d.report_day=m.report_day %s
                where d.channel_id=? and d.report_day between ? and ?
                group by d.report_day,d.report_created,d.imported_at order by d.report_day
                """.formatted(videoFilter), (rs, row) -> {
            long impressions = rs.getLong(4);
            Long impressionsOrNull = rs.wasNull() ? null : impressions;
            long clicks = rs.getLong(5);
            Long clicksOrNull = rs.wasNull() ? null : clicks;
            return new Day(rs.getObject(1, LocalDate.class), rs.getObject(2, OffsetDateTime.class),
                    rs.getObject(3, OffsetDateTime.class), impressionsOrNull, clicksOrNull);
        }, arguments);
        var observed = rows.stream().filter(row -> row.impressions() != null).toList();
        long impressions = observed.stream().mapToLong(Day::impressions).sum();
        long clicks = observed.stream().mapToLong(Day::clicks).sum();
        long requested = ChronoUnit.DAYS.between(from, to) + 1;
        return new Totals(observed.isEmpty() ? "UNAVAILABLE" : observed.size() == requested ? "AVAILABLE" : "PARTIAL",
                observed.isEmpty() ? null : impressions, observed.isEmpty() ? null : clicks,
                impressions == 0 ? null : (double) clicks / impressions, requested, rows);
    }

    public ByType byType(String channel, String video, LocalDate from, LocalDate to) {
        String videoFilter = video == null ? "" : " and video_id=?";
        Object[] arguments = video == null ? new Object[]{channel, from, to} : new Object[]{channel, from, to, video};
        var data = jdbc.query("""
                select element_type,sum(impressions),sum(clicks) from advisor_end_screen_type
                where channel_id=? and report_day between ? and ? %s
                group by element_type order by sum(impressions) desc,element_type
                """.formatted(videoFilter), (rs, n) -> {
            long impressions = rs.getLong(2);
            long clicks = rs.getLong(3);
            return new Element(rs.getString(1), impressions, clicks,
                    impressions > 0 ? (double) clicks / impressions : null);
        }, arguments);
        var coverage = jdbc.queryForObject("""
                select count(distinct report_day), max(report_day) from advisor_end_screen_type
                where channel_id=? and report_day between ? and ? %s
                """.formatted(videoFilter), (rs, row) -> new Coverage(rs.getLong(1), rs.getObject(2, LocalDate.class)), arguments);
        long requested = ChronoUnit.DAYS.between(from, to) + 1;
        return new ByType(data.isEmpty() ? "NO_ROWS" : coverage.days() == requested ? "AVAILABLE" : "PARTIAL",
                requested, coverage.days(), coverage.last(), data);
    }

    private record Coverage(long days, LocalDate last) {}
    public record ByType(String availability, long requestedDays, long daysWithRows, LocalDate lastObservedDay,
                         List<Element> elements) {}
    public record Element(String elementType, long impressions, long clicks, Double clickRate) {}
    public record VideoDay(String videoId, String elementType, long impressions, long clicks) {}
    public record Day(LocalDate date, OffsetDateTime upstreamCreatedAt, OffsetDateTime importedAt,
                      Long impressions, Long clicks) {}
    public record Totals(String availability, Long endScreenElementImpressions, Long endScreenElementClicks,
                         Double endScreenElementClickRate, long requestedDays, List<Day> daily) {}
}
