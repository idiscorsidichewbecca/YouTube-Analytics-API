package it.chewbecca.youtubeanalytics.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.SortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.api.model.TrendGranularity;
import it.chewbecca.youtubeanalytics.service.YoutubeReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/content")
@Tag(name = "Content reports")
public class ContentReportController {

    private final YoutubeReportService reportService;

    public ContentReportController(YoutubeReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(operationId = "listContentPerformance", summary = "Elenca video e Short con metriche lifetime e del periodo")
    public ApiModels.ContentListResponse content(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") ContentType type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "VIEWS") SortBy sortBy,
            @RequestParam(defaultValue = "DESC") SortOrder order) {
        return reportService.content(startDate, endDate, type, query, page, size, sortBy, order);
    }

    @GetMapping("/summary")
    @Operation(operationId = "getContentTypeSummary", summary = "Confronta le prestazioni aggregate di video e Short")
    public ApiModels.ContentSummaryResponse contentSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.contentSummary(startDate, endDate);
    }

    @GetMapping("/{videoId}")
    @Operation(operationId = "getContentDetails", summary = "Dettaglio di un video o Short del canale")
    public ApiModels.ContentDetailResponse detail(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.contentDetail(videoId, startDate, endDate);
    }

    @GetMapping("/{videoId}/trend")
    @Operation(operationId = "getContentTrend", summary = "Andamento giornaliero o mensile di un contenuto")
    public ApiModels.TrendResponse trend(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "DAY") TrendGranularity granularity) {
        return reportService.contentTrend(videoId, startDate, endDate, granularity);
    }

    @GetMapping("/{videoId}/traffic-sources")
    @Operation(operationId = "getContentTrafficSources", summary = "Sorgenti di traffico del contenuto")
    public ApiModels.BreakdownResponse trafficSources(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.trafficSources(videoId, startDate, endDate);
    }

    @GetMapping("/{videoId}/countries")
    @Operation(operationId = "getContentCountries", summary = "Paesi principali per visualizzazioni del contenuto")
    public ApiModels.BreakdownResponse countries(
            @PathVariable String videoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "25") int limit) {
        return reportService.countries(videoId, startDate, endDate, limit);
    }
}
