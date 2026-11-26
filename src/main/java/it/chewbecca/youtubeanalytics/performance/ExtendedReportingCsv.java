package it.chewbecca.youtubeanalytics.performance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ExtendedReportingCsv {
    private ExtendedReportingCsv() {}

    static List<ReachRow> reach(Reader input, String channel, LocalDate day) throws IOException {
        var csv = new Csv(input, channel, day);
        int source = csv.column("traffic_source_type");
        int detail = csv.column("traffic_source_detail");
        int device = csv.column("device_type");
        int os = csv.column("operating_system");
        int impressions = csv.column("video_thumbnail_impressions");
        int ctr = csv.column("video_thumbnail_impressions_ctr");
        List<ReachRow> result = new ArrayList<>();
        Set<List<String>> records = new HashSet<>();
        for (var row = csv.next(); row != null; row = csv.next()) {
            if (!records.add(List.of(csv.video(row), row.get(source), row.get(detail), row.get(device), row.get(os)))) {
                throw new IOException("Dimensioni reach duplicate");
            }
            Long count = count(row.get(impressions));
            Double ratio = number(row.get(ctr));
            if (count == null || (ratio != null && ratio > 1)) throw new IOException("Riga reach non valida");
            result.add(new ReachRow(csv.video(row), row.get(source), row.get(detail), row.get(device), row.get(os),
                    count, ratio));
        }
        return result;
    }

    static List<SubtitleRow> subtitles(Reader input, String channel, LocalDate day) throws IOException {
        var csv = new Csv(input, channel, day);
        int language = csv.column("subtitle_language");
        int translated = csv.column("subtitle_language_autotranslated");
        int engaged = csv.column("engaged_views");
        int views = csv.column("views");
        int minutes = csv.column("watch_time_minutes");
        int premiumViews = csv.column("red_views");
        int premiumMinutes = csv.column("red_watch_time_minutes");
        List<SubtitleRow> result = new ArrayList<>();
        for (var row = csv.next(); row != null; row = csv.next()) {
            result.add(new SubtitleRow(csv.video(row), row.get(language), row.get(translated), count(row.get(engaged)),
                    count(row.get(views)), number(row.get(minutes)), count(row.get(premiumViews)),
                    number(row.get(premiumMinutes))));
        }
        return result;
    }

    private static Long count(String value) throws IOException {
        if (missing(value)) return null;
        try {
            long count = Long.parseLong(value);
            if (count >= 0) return count;
        } catch (NumberFormatException ignored) {
            // Report invalid numbers as a failed day, without replacing the existing data.
        }
        throw new IOException("Conteggio Reporting API non valido");
    }

    private static Double number(String value) throws IOException {
        if (missing(value)) return null;
        try {
            double number = Double.parseDouble(value);
            if (Double.isFinite(number) && number >= 0) return number;
        } catch (NumberFormatException ignored) {
            // Report invalid numbers as a failed day, without replacing the existing data.
        }
        throw new IOException("Metrica Reporting API non valida");
    }

    private static boolean missing(String value) {
        return value.isBlank() || "NULL".equalsIgnoreCase(value);
    }

    record ReachRow(String video, String source, String detail, String device, String os, long impressions, Double ctr) {}

    record SubtitleRow(String video, String language, String autoTranslated, Long engagedViews, Long views,
                       Double watchMinutes, Long premiumViews, Double premiumWatchMinutes) {}

    private static final class Csv {
        private final BufferedReader input;
        private final String channel;
        private final String day;
        private final List<String> header;
        private final int dateIndex;
        private final int ownerIndex;
        private final int videoIndex;

        private Csv(Reader input, String channel, LocalDate day) throws IOException {
            this.input = new BufferedReader(input);
            this.channel = channel;
            this.day = day.format(DateTimeFormatter.BASIC_ISO_DATE);
            String firstLine = this.input.readLine();
            if (firstLine == null) throw new IOException("Intestazione Reporting API assente");
            header = ReachCsv.columns(firstLine.replaceFirst("^\uFEFF", ""));
            dateIndex = column("date");
            ownerIndex = column("channel_id");
            videoIndex = column("video_id");
        }

        private int column(String name) throws IOException {
            return ReachCsv.column(header, name);
        }

        private String video(List<String> row) {
            return row.get(videoIndex);
        }

        private List<String> next() throws IOException {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) continue;
                var row = ReachCsv.columns(line);
                if (row.size() != header.size() || !day.equals(row.get(dateIndex))) {
                    throw new IOException("Riga Reporting API con schema o data inattesi");
                }
                if (missing(row.get(ownerIndex)) || missing(video(row))) continue;
                if (!channel.equals(row.get(ownerIndex)) || !video(row).matches("[A-Za-z0-9_-]{11}")) {
                    throw new IOException("Riga Reporting API con canale o video inatteso");
                }
                return row;
            }
            return null;
        }
    }
}
