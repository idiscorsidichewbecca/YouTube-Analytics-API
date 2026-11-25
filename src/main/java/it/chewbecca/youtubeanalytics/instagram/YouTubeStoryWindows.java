package it.chewbecca.youtubeanalytics.instagram;

import java.time.*;
import java.util.*;

/** Half-open intervals: actual 24-hour story lifetime, local Italian protected evening. */
public final class YouTubeStoryWindows {
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private YouTubeStoryWindows() {}
    public record Release(String videoId, Instant at) {}
    public static Optional<LocalDate> conflict(Instant storyAt, Collection<Release> releases) {
        Instant expiry = storyAt.plus(Duration.ofHours(24));
        return releases.stream().map(r -> r.at().atZone(ROME).toLocalDate()).distinct().sorted()
                .filter(day -> storyAt.isBefore(day.plusDays(1).atStartOfDay(ROME).toInstant())
                        && expiry.isAfter(day.atTime(17, 0).atZone(ROME).toInstant())).findFirst();
    }
    /** Earliest usable day; interval between appointments is a minimum, not an exact cadence. */
    public static Optional<LocalDate> nextDay(LocalDate start, LocalDate last, int morning, int evening,
            Instant earliest, Collection<Release> releases, Set<LocalDate> reserved) {
        for (LocalDate day = start; !day.isAfter(last); day = day.plusDays(1)) {
            Instant q = day.atTime(morning, 0).atZone(ROME).toInstant();
            Instant a = day.atTime(evening, 0).atZone(ROME).toInstant();
            if (!reserved.contains(day) && q.isAfter(earliest)
                    && conflict(q, releases).isEmpty() && conflict(a, releases).isEmpty()) return Optional.of(day);
        }
        return Optional.empty();
    }
}
