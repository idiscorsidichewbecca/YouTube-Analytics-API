package it.chewbecca.youtubeanalytics.meta.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.meta.api.model.InstagramMediaType;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortBy;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortOrder;
import it.chewbecca.youtubeanalytics.meta.service.InstagramAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/meta/instagram")
@Tag(name = "Instagram Analytics")
public class InstagramAnalyticsController {

    private final InstagramAnalyticsService service;

    public InstagramAnalyticsController(InstagramAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/account")
    @Operation(operationId = "getInstagramAccount", summary = "Metadati dell'account Instagram collegato")
    public MetaApiModels.InstagramAccount account() {
        return service.account();
    }

    @GetMapping("/summary")
    @Operation(operationId = "getInstagramSummary", summary = "Riepilogo Instagram nel periodo")
    public MetaApiModels.InstagramSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return service.summary(startDate, endDate);
    }

    @GetMapping("/trend")
    @Operation(operationId = "getInstagramTrend", summary = "Trend giornaliero delle metriche Instagram")
    public MetaApiModels.TrendResponse trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String metrics) {
        return service.trend(startDate, endDate, metrics);
    }

    @GetMapping("/insights")
    @Operation(operationId = "getInstagramInsights", summary = "Insight Instagram generici per le metriche richieste")
    public MetaApiModels.InsightsResponse insights(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false) String period,
            @RequestParam(name = "metricType", required = false) String metricType,
            @RequestParam(required = false) String breakdown,
            @RequestParam(required = false) String timeframe) {
        return service.insights(startDate, endDate, metrics, period, metricType, breakdown, timeframe);
    }

    @GetMapping("/media")
    @Operation(operationId = "listInstagramMedia", summary = "Elenca post, caroselli, video e Reel Instagram")
    public MetaApiModels.InstagramMediaListResponse media(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") InstagramMediaType type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PUBLISHED_AT") MetaSortBy sortBy,
            @RequestParam(defaultValue = "DESC") MetaSortOrder order,
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.media(startDate, endDate, type, query, page, size, sortBy, order, includeInsights);
    }

    @GetMapping("/reels")
    @Operation(operationId = "listInstagramReels", summary = "Elenca i Reel Instagram")
    public MetaApiModels.InstagramMediaListResponse reels(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PUBLISHED_AT") MetaSortBy sortBy,
            @RequestParam(defaultValue = "DESC") MetaSortOrder order,
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.media(startDate, endDate, InstagramMediaType.REEL, query, page, size, sortBy, order, includeInsights);
    }

    @GetMapping("/stories")
    @Operation(operationId = "listInstagramStories", summary = "Elenca le storie Instagram ancora attive")
    public MetaApiModels.InstagramMediaListResponse stories(
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.stories(includeInsights);
    }

    @GetMapping("/media/{mediaId}")
    @Operation(operationId = "getInstagramMedia", summary = "Dettaglio di un contenuto Instagram")
    public MetaApiModels.InstagramMediaItem media(
            @PathVariable String mediaId,
            @RequestParam(defaultValue = "true") boolean includeInsights) {
        return service.media(mediaId, includeInsights);
    }

    @GetMapping("/media/{mediaId}/insights")
    @Operation(operationId = "getInstagramMediaInsights", summary = "Insight di un post, Reel o storia Instagram")
    public MetaApiModels.InsightsResponse mediaInsights(
            @PathVariable String mediaId,
            @RequestParam(required = false) String metrics) {
        return service.mediaInsights(mediaId, metrics);
    }

    @GetMapping("/top-content")
    @Operation(operationId = "getInstagramTopContent", summary = "Classifica dei migliori contenuti Instagram")
    public MetaApiModels.InstagramMediaListResponse topContent(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") InstagramMediaType type,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "REACH") MetaSortBy sortBy) {
        return service.topContent(startDate, endDate, type, limit, sortBy);
    }
}
