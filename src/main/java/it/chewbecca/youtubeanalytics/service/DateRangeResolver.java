package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class DateRangeResolver {

    public static final ZoneId YOUTUBE_ANALYTICS_ZONE = ZoneId.of("America/Los_Angeles");

    private final YoutubeProperties properties;
    private final Clock clock;

    public DateRangeResolver(YoutubeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public DateRange resolve(LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDate latestCompleteDay = LocalDate.now(clock.withZone(YOUTUBE_ANALYTICS_ZONE)).minusDays(1);
        LocalDate end = requestedEnd == null ? latestCompleteDay : requestedEnd;
        LocalDate start = requestedStart == null ? end.minusDays(properties.defaultReportDays() - 1L) : requestedStart;

        if (end.isAfter(latestCompleteDay)) {
            throw new IllegalArgumentException("endDate non può essere successiva all'ultimo giorno completo: " + latestCompleteDay);
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate deve essere precedente o uguale a endDate");
        }
        if (start.isBefore(LocalDate.of(2005, 2, 14))) {
            throw new IllegalArgumentException("startDate non può precedere la nascita di YouTube (2005-02-14)");
        }
        return new DateRange(start, end);
    }

    public record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
