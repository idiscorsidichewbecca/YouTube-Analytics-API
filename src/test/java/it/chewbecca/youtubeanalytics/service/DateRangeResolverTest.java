package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.config.YoutubeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeResolverTest {

    private final YoutubeProperties properties = new YoutubeProperties(
            28,
            180,
            true,
            LocalDate.of(2019, 1, 1),
            100,
            List.of(),
            List.of()
    );
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private final DateRangeResolver resolver = new DateRangeResolver(properties, clock);

    @Test
    void defaultsToLast28CompleteYoutubeDays() {
        DateRangeResolver.DateRange range = resolver.resolve(null, null);
        assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 6, 17));
    }

    @Test
    void rejectsFutureEndDate() {
        assertThatThrownBy(() -> resolver.resolve(null, LocalDate.of(2026, 7, 15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
