package it.chewbecca.youtubeanalytics.instagram;

import java.util.*;

/** Comparable observations collected at age 20-21h, local Italian publication hour. */
public final class QuizSlots {
    private QuizSlots() {}
    public record Sample(int hour, long reach) {}
    public record Evidence(int hour, int samples, Double medianReach, String confidence) {}
    public record Choice(int hour, String method, List<Evidence> evidence) {}
    public static Choice choose(String kind, List<Sample> samples, int ordinal) {
        List<Integer> hours = switch (kind) {
            case "QUESTION" -> List.of(9, 8, 10, 11);
            case "ANSWER" -> List.of(20, 19, 21, 18, 22);
            default -> throw new IllegalArgumentException("Tipo storia non valido");
        };
        var evidence = hours.stream().map(h -> {
            var values = samples.stream().filter(s -> s.hour == h && s.reach >= 0).map(Sample::reach).sorted().toList();
            int n = values.size();
            Double median = n == 0 ? null : n % 2 == 1 ? (double) values.get(n/2) : values.get(n/2-1)/2.0 + values.get(n/2)/2.0;
            return new Evidence(h, n, median, n >= 8 ? "HIGH_SAMPLE" : n >= 3 ? "MEDIUM_SAMPLE" : "LOW_SAMPLE");
        }).toList();
        var ranked = evidence.stream().filter(e -> e.samples >= 3)
                .sorted(Comparator.comparing(Evidence::medianReach).reversed().thenComparing(Comparator.comparingInt(Evidence::samples).reversed())).toList();
        if (ranked.size() >= 2) {
            return new Choice(ranked.getFirst().hour, "BEST_OBSERVED_MEDIAN", evidence);
        }
        var least = evidence.stream().mapToInt(Evidence::samples).min().orElse(0);
        var candidates = evidence.stream().filter(e -> e.samples == least).toList();
        return new Choice(candidates.get(Math.floorMod(ordinal, candidates.size())).hour,
                "EXPLORATION_INSUFFICIENT_EVIDENCE", evidence);
    }
}
