package it.chewbecca.youtubeanalytics.telegram.service;

import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.SortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.service.YoutubeReportService;
import it.chewbecca.youtubeanalytics.service.YoutubeRevenueService;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramAnalyticsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAnalyticsServiceTest {

    private YoutubeReportService reportService;
    private YoutubeRevenueService revenueService;
    private TelegramAnalyticsService service;

    @BeforeEach
    void setUp() {
        reportService = mock(YoutubeReportService.class);
        revenueService = mock(YoutubeRevenueService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);
        service = new TelegramAnalyticsService(
                reportService,
                revenueService,
                new TelegramCommandCatalog(),
                new TelegramAnalyticsProperties("", 30),
                clock
        );
    }

    @Test
    void analyzesCurrent28DaysAgainstThePrevious28Days() {
        LocalDate currentStart = LocalDate.of(2026, 7, 16);
        LocalDate currentEnd = LocalDate.of(2026, 8, 12);
        LocalDate previousStart = LocalDate.of(2026, 6, 18);
        LocalDate previousEnd = LocalDate.of(2026, 7, 15);
        when(reportService.channelSummary(currentStart, currentEnd))
                .thenReturn(summary(currentStart, currentEnd, metrics(1200, 120, 20)));
        when(reportService.channelSummary(previousStart, previousEnd))
                .thenReturn(summary(previousStart, previousEnd, metrics(1000, 100, 10)));

        String answer = service.answer("come sta andando il canale adesso?", "chewbecca_strategist_bot");

        assertThat(answer)
                .contains("Ultimi 28 giorni")
                .contains("Visualizzazioni: 1.200 vs 1.000")
                .contains("+20,00%");
        verify(reportService).channelSummary(currentStart, currentEnd);
        verify(reportService).channelSummary(previousStart, previousEnd);
    }

    @Test
    void asksForTenVideosWhenNaturalLanguageRequestsTopVideos() {
        LocalDate start = LocalDate.of(2026, 7, 16);
        LocalDate end = LocalDate.of(2026, 8, 12);
        when(reportService.content(
                null, null, ContentType.VIDEO, null, 0, 10, SortBy.VIEWS, SortOrder.DESC))
                .thenReturn(new ApiModels.ContentListResponse(
                        new ApiModels.ReportPeriod(start, end, "America/Los_Angeles"),
                        ContentType.VIDEO,
                        null,
                        0,
                        10,
                        0,
                        0,
                        SortBy.VIEWS,
                        SortOrder.DESC,
                        List.of()
                ));

        String answer = service.answer("dimmi i top video", "chewbecca_strategist_bot");

        assertThat(answer).contains("Top 10 video per visualizzazioni");
        verify(reportService).content(
                null, null, ContentType.VIDEO, null, 0, 10, SortBy.VIEWS, SortOrder.DESC);
    }

    @Test
    void comparesLastCompleteMonthWithRequestedMonth() {
        LocalDate latestStart = LocalDate.of(2026, 7, 1);
        LocalDate latestEnd = LocalDate.of(2026, 7, 31);
        LocalDate requestedStart = LocalDate.of(2025, 9, 1);
        LocalDate requestedEnd = LocalDate.of(2025, 9, 30);
        when(reportService.channelSummary(latestStart, latestEnd))
                .thenReturn(summary(latestStart, latestEnd, metrics(1200, 100, 20)));
        when(reportService.channelSummary(requestedStart, requestedEnd))
                .thenReturn(summary(requestedStart, requestedEnd, metrics(900, 80, 10)));

        String answer = service.answer("/confrontomese 2025-09", "chewbecca_strategist_bot");

        assertThat(answer).contains("2026-07").contains("2025-09");
        verify(reportService).channelSummary(latestStart, latestEnd);
        verify(reportService).channelSummary(requestedStart, requestedEnd);
    }

    private ApiModels.ChannelSummaryResponse summary(LocalDate start,
                                                     LocalDate end,
                                                     ApiModels.AnalyticsMetrics metrics) {
        return new ApiModels.ChannelSummaryResponse(
                "channel",
                "I Discorsi di Chewbecca",
                "@idiscorsidichewbecca",
                null,
                new ApiModels.LifetimeChannelMetrics(9000, false, true, 2_000_000, 250),
                new ApiModels.ReportPeriod(start, end, "America/Los_Angeles"),
                metrics,
                Instant.parse("2026-08-13T12:00:00Z"),
                null
        );
    }

    private ApiModels.AnalyticsMetrics metrics(long views, double hours, long subscribers) {
        return new ApiModels.AnalyticsMetrics(
                views,
                views,
                10,
                100,
                5,
                hours * 60,
                hours,
                120,
                60,
                subscribers,
                0,
                subscribers,
                5
        );
    }
}
