package it.chewbecca.youtubeanalytics.community;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public record CommentPeriod(LocalDate from, LocalDate to) {
    public static final ZoneId ROME = ZoneId.of("Europe/Rome");

    public static CommentPeriod resolve(Clock clock, String period, Integer days, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(ROME));
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var range = switch (period == null ? "THIS_WEEK" : period) {
            case "THIS_WEEK" -> new CommentPeriod(monday, today);
            case "PREVIOUS_WEEK" -> new CommentPeriod(monday.minusWeeks(1), monday.minusDays(1));
            case "LAST_DAYS" -> {
                if (days == null || days < 1 || days > 366) {
                    throw new IllegalArgumentException("Indica un numero di giorni da 1 a 366");
                }
                yield new CommentPeriod(today.minusDays(days - 1L), today);
            }
            case "CUSTOM" -> new CommentPeriod(from, to);
            default -> throw new IllegalArgumentException("Periodo commenti sconosciuto");
        };
        if (range.from() == null || range.to() == null || range.from().isAfter(range.to())
                || range.to().isAfter(today) || range.from().plusDays(365).isBefore(range.to())) {
            throw new IllegalArgumentException("Indica un intervallo valido e non futuro, di massimo 366 giorni");
        }
        return range;
    }

    public boolean includes(Instant timestamp) {
        LocalDate day = timestamp.atZone(ROME).toLocalDate();
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
