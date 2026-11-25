package it.chewbecca.youtubeanalytics.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.RevenueSortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.api.model.TrendGranularity;
import it.chewbecca.youtubeanalytics.service.YoutubeRevenueService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/revenue")
@Tag(name = "Revenue reports")
public class RevenueReportController {

    private final YoutubeRevenueService revenueService;

    public RevenueReportController(YoutubeRevenueService revenueService) {
        this.revenueService = revenueService;
    }

    @GetMapping("/summary")
    @Operation(operationId = "getRevenueSummary", summary = "Ricavi stimati complessivi del canale")
    public ApiModels.RevenueSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "EUR") String currency) {
        return revenueService.summary(startDate, endDate, currency);
    }

    @GetMapping("/trend")
    @Operation(operationId = "getRevenueTrend", summary = "Andamento giornaliero o mensile dei ricavi del canale")
    public ApiModels.RevenueTrendResponse trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "DAY") TrendGranularity granularity,
            @RequestParam(defaultValue = "EUR") String currency) {
        return revenueService.channelTrend(startDate, endDate, granularity, currency);
    }

    @GetMapping("/content")
    @Operation(operationId = "listContentRevenue", summary = "Ricavi stimati di ogni video e Short")
    public ApiModels.RevenueContentListResponse content(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") ContentType type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "ESTIMATED_REVENUE") RevenueSortBy sortBy,
            @RequestParam(defaultValue = "DESC") SortOrder order,
            @RequestParam(defaultValue = "EUR") String currency) {
        return revenueService.content(startDate, endDate, type, query, page, size, sortBy, order, currency);
    }

    @GetMapping("/content/{videoId}")
    @Operation(operationId = "getContentRevenue", summary = "Ricavi stimati di un singolo video o Short")
    public ApiModels.RevenueContentDetailResponse contentDetail(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "EUR") String currency) {
        return revenueService.contentDetail(videoId, startDate, endDate, currency);
    }

    @GetMapping("/content/{videoId}/trend")
    @Operation(operationId = "getContentRevenueTrend", summary = "Andamento dei ricavi di un singolo contenuto")
    public ApiModels.RevenueTrendResponse contentTrend(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "DAY") TrendGranularity granularity,
            @RequestParam(defaultValue = "EUR") String currency) {
        return revenueService.contentTrend(videoId, startDate, endDate, granularity, currency);
    }
}
