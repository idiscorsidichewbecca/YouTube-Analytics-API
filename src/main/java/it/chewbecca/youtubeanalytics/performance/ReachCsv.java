package it.chewbecca.youtubeanalytics.performance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class ReachCsv {
    private ReachCsv() {}

    static List<VideoDay> read(Reader input, String channel, LocalDate reportDay) throws IOException {
        var reader = new BufferedReader(input);
        String header = reader.readLine();
        if (header == null) throw new IOException("Report reach CSV senza intestazione");
        var keys = columns(header.replaceFirst("^\uFEFF", ""));
        int date = column(keys, "date");
        int owner = column(keys, "channel_id");
        int video = column(keys, "video_id");
        int impressions = column(keys, "video_thumbnail_impressions");
        int ctr = column(keys, "video_thumbnail_impressions_ctr");

        var rows = new ArrayList<VideoDay>();
        var seen = new HashSet<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            var values = columns(line);
            if (values.size() != keys.size() || !channel.equals(values.get(owner))
                    || !reportDay.equals(LocalDate.parse(values.get(date), DateTimeFormatter.BASIC_ISO_DATE))) {
                throw new IOException("Report reach con giorno, canale o colonne inattesi");
            }
            String id = values.get(video);
            if (id.isBlank()) continue; // Aggregated/anonymous records carry no attributable video ID.
            if (!id.matches("[A-Za-z0-9_-]{11}") || !seen.add(id)) {
                throw new IOException("Report reach con identificativo video invalido o duplicato");
            }
            long count = Long.parseLong(values.get(impressions));
            Double ratio = values.get(ctr).isBlank() ? null : Double.valueOf(values.get(ctr));
            if (count < 0 || (ratio != null && (!Double.isFinite(ratio) || ratio < 0 || ratio > 1))) {
                throw new IOException("Metriche reach fuori intervallo");
            }
            rows.add(new VideoDay(id, count, ratio));
        }
        return rows;
    }

    static int column(List<String> keys, String name) throws IOException {
        int index = keys.indexOf(name);
        if (index < 0) throw new IOException("Colonna reach mancante: " + name);
        return index;
    }

    static List<String> columns(String line) throws IOException {
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                result.add(value.toString().trim());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (quoted) throw new IOException("Riga CSV con virgolette sbilanciate");
        result.add(value.toString().trim());
        return result;
    }

    record VideoDay(String videoId, long impressions, Double ctr) {}
}
