package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ReelSlotPlanner {
    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final int MIN_REELS = 8;

    record Observation(ZonedDateTime at, int cohort, double interactions) {}
    record Slot(OffsetDateTime at, double score, int comparableReels, int matchingDay, int matchingHour,
                int matchingDayAndHour, String evidence) {}

    private ReelSlotPlanner() {}

    static List<Observation> observations(List<JsonNode> media, ZonedDateTime now) {
        var observations = new ArrayList<Observation>();
        for (JsonNode item : media) {
            if (!"REELS".equals(item.path("media_product_type").asText())) continue;
            try {
                var timestamp = item.path("timestamp").asText().replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2");
                var published = OffsetDateTime.parse(timestamp).atZoneSameInstant(ROME);
                var age = Duration.between(published, now).toDays();
                if (age < 7 || age > 90 || !item.path("like_count").isIntegralNumber()
                        || !item.path("comments_count").isIntegralNumber()) continue;
                double interactions = item.path("like_count").asLong() + 3.0 * item.path("comments_count").asLong();
                int cohort = age < 14 ? 1 : age < 28 ? 2 : age < 56 ? 3 : 4;
                if (interactions >= 0) observations.add(new Observation(published, cohort, interactions));
            } catch (DateTimeParseException ignored) {
                // Ignore malformed or missing timestamps.
            }
        }
        return observations;
    }

    static List<Slot> rank(ZonedDateTime now, Set<LocalDate> occupied, List<Observation> observations) {
        Map<Integer, List<Observation>> cohorts = observations.stream().collect(Collectors.groupingBy(Observation::cohort));
        var comparable = cohorts.values().stream().filter(group -> group.size() >= 3)
                .flatMap(group -> {
                    double median = median(group.stream().map(Observation::interactions).sorted().toList());
                    return group.stream().map(o -> new Observation(o.at(), o.cohort(),
                            Math.max(-2, Math.min(2, Math.log((o.interactions() + 1) / (median + 1))))));
                }).toList();
        var candidates = new ArrayList<Slot>();
        for (var day = now.toLocalDate(); day.isBefore(now.toLocalDate().plusDays(21)); day = day.plusDays(1)) {
            if (occupied.contains(day)) continue;
            for (int hour = 0; hour < 24; hour++) {
                var at = day.atTime(hour, 0).atZone(ROME);
                if (at.getHour() == hour && at.isAfter(now.plusMinutes(20))) {
                    candidates.add(score(at, comparable));
                }
            }
        }
        return candidates.stream().sorted(Comparator.comparingDouble(Slot::score).reversed()
                .thenComparing(Slot::at)).toList();
    }

    private static Slot score(ZonedDateTime at, List<Observation> sample) {
        var day = sample.stream().filter(o -> o.at().getDayOfWeek() == at.getDayOfWeek()).toList();
        var hour = sample.stream().filter(o -> nearHour(o.at().getHour(), at.getHour())).toList();
        var overlap = day.stream().filter(hour::contains).toList();
        double fallback = exploratory(at.getDayOfWeek(), at.getHour());
        boolean supported = sample.size() >= MIN_REELS && (hour.size() >= 3 || overlap.size() >= 2);
        double score = supported ? 100 + 4 * shrunk(overlap) + 2 * shrunk(hour) + shrunk(day)
                + fallback / 100 : fallback;
        return new Slot(at.toOffsetDateTime(), score, sample.size(), day.size(), hour.size(), overlap.size(),
                supported ? "Storico Instagram: like + 3 × commenti, normalizzati per età; fascia di un'ora"
                          : "Fascia esplorativa, dati Instagram insufficienti per questo orario");
    }

    private static boolean nearHour(int left, int right) {
        return Math.floorMod(left - right + 1, 24) <= 2;
    }

    private static double shrunk(List<Observation> group) {
        if (group.isEmpty()) return 0;
        return median(group.stream().map(Observation::interactions).sorted().toList())
                * group.size() / (group.size() + 5.0);
    }

    private static double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return (sorted.get((sorted.size() - 1) / 2) + sorted.get(middle)) / 2;
    }

    private static double exploratory(DayOfWeek day, int hour) {
        if (day == DayOfWeek.WEDNESDAY && hour == 18) return 9;
        if (day == DayOfWeek.THURSDAY && hour == 9) return 8;
        if (day == DayOfWeek.WEDNESDAY && hour == 12) return 7;
        if (hour == 18 && day.getValue() <= 4) return 5;
        if (hour == 19) return 3;
        return 0;
    }
}
