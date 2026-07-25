package it.chewbecca.youtubeanalytics.meta.service;

import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Component
public class MetaDateRangeResolver {

    private final MetaProperties properties;
    private final Clock clock;

    public MetaDateRangeResolver(MetaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public MetaApiModels.ReportPeriod resolve(LocalDate startDate, LocalDate endDate) {
        LocalDate defaultEnd = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        LocalDate resolvedEnd = endDate == null ? defaultEnd : endDate;
        LocalDate resolvedStart = startDate == null
                ? resolvedEnd.minusDays(Math.max(1, properties.defaultReportDays()) - 1L)
                : startDate;
        if (resolvedStart.isAfter(resolvedEnd)) {
            throw new IllegalArgumentException("startDate non può essere successiva a endDate");
        }
        long days = ChronoUnit.DAYS.between(resolvedStart, resolvedEnd) + 1;
        if (days > Math.max(1, properties.maxInsightRangeDays())) {
            throw new IllegalArgumentException(
                    "L'intervallo Meta non può superare " + properties.maxInsightRangeDays() + " giorni"
            );
        }
        if (resolvedEnd.isAfter(defaultEnd.plusDays(1))) {
            throw new IllegalArgumentException("endDate non può essere nel futuro");
        }
        return new MetaApiModels.ReportPeriod(resolvedStart, resolvedEnd, "UTC");
    }
}
