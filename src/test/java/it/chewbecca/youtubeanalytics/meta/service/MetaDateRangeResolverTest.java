package it.chewbecca.youtubeanalytics.meta.service;

import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaDateRangeResolverTest {

    private final MetaDateRangeResolver resolver = new MetaDateRangeResolver(
            new MetaProperties(
                    true,
                    "app",
                    "secret",
                    "config",
                    URI.create("http://localhost/callback"),
                    "v25.0",
                    null,
                    null,
                    28,
                    90,
                    500,
                    60
            ),
            Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void resolvesDefaultTwentyEightCompleteDays() {
        MetaApiModels.ReportPeriod period = resolver.resolve(null, null);

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 6, 27));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(period.analyticsTimeZone()).isEqualTo("UTC");
    }

    @Test
    void rejectsRangesLongerThanConfiguredMaximum() {
        assertThatThrownBy(() -> resolver.resolve(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("90 giorni");
    }
}
