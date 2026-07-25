package it.chewbecca.youtubeanalytics.meta.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.meta.api.model.FacebookContentType;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortBy;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaSortOrder;
import it.chewbecca.youtubeanalytics.meta.service.FacebookAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/meta/facebook")
@Tag(name = "Facebook Analytics")
public class FacebookAnalyticsController {

    private final FacebookAnalyticsService service;

    public FacebookAnalyticsController(FacebookAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/page")
    @Operation(operationId = "getFacebookPage", summary = "Metadati della Pagina Facebook collegata")
    public MetaApiModels.FacebookPage page() {
        return service.page();
    }

    @GetMapping("/summary")
    @Operation(operationId = "getFacebookSummary", summary = "Riepilogo Facebook nel periodo")
    public MetaApiModels.FacebookSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return service.summary(startDate, endDate);
    }

    @GetMapping("/trend")
    @Operation(operationId = "getFacebookTrend", summary = "Trend giornaliero delle metriche Facebook")
    public MetaApiModels.TrendResponse trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String metrics) {
        return service.trend(startDate, endDate, metrics);
    }

    @GetMapping("/insights")
    @Operation(operationId = "getFacebookInsights", summary = "Insight Facebook generici per le metriche richieste")
    public MetaApiModels.InsightsResponse insights(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String metrics,
            @RequestParam(defaultValue = "day") String period) {
        return service.insights(startDate, endDate, metrics, period);
    }

    @GetMapping("/posts")
    @Operation(operationId = "listFacebookPosts", summary = "Elenca post Facebook con contatori e insight opzionali")
    public MetaApiModels.FacebookContentListResponse posts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") FacebookContentType type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PUBLISHED_AT") MetaSortBy sortBy,
            @RequestParam(defaultValue = "DESC") MetaSortOrder order,
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.posts(startDate, endDate, type, query, page, size, sortBy, order, includeInsights);
    }

    @GetMapping("/posts/{postId}")
    @Operation(operationId = "getFacebookPost", summary = "Dettaglio di un post Facebook")
    public MetaApiModels.FacebookContentItem post(
            @PathVariable String postId,
            @RequestParam(defaultValue = "true") boolean includeInsights) {
        return service.post(postId, includeInsights);
    }

    @GetMapping("/posts/{postId}/insights")
    @Operation(operationId = "getFacebookPostInsights", summary = "Insight di un post Facebook")
    public MetaApiModels.InsightsResponse postInsights(
            @PathVariable String postId,
            @RequestParam(required = false) String metrics) {
        return service.postInsights(postId, metrics);
    }

    @GetMapping("/videos")
    @Operation(operationId = "listFacebookVideos", summary = "Elenca i video Facebook")
    public MetaApiModels.FacebookVideoListResponse videos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PUBLISHED_AT") MetaSortBy sortBy,
            @RequestParam(defaultValue = "DESC") MetaSortOrder order,
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.videos(startDate, endDate, false, query, page, size, sortBy, order, includeInsights);
    }

    @GetMapping("/reels")
    @Operation(operationId = "listFacebookReels", summary = "Elenca i Reel Facebook")
    public MetaApiModels.FacebookVideoListResponse reels(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PUBLISHED_AT") MetaSortBy sortBy,
            @RequestParam(defaultValue = "DESC") MetaSortOrder order,
            @RequestParam(defaultValue = "false") boolean includeInsights) {
        return service.videos(startDate, endDate, true, query, page, size, sortBy, order, includeInsights);
    }

    @GetMapping("/videos/{videoId}")
    @Operation(operationId = "getFacebookVideo", summary = "Dettaglio di un video Facebook")
    public MetaApiModels.FacebookVideoItem video(
            @PathVariable String videoId,
            @RequestParam(defaultValue = "true") boolean includeInsights) {
        return service.video(videoId, includeInsights);
    }

    @GetMapping("/videos/{videoId}/insights")
    @Operation(operationId = "getFacebookVideoInsights", summary = "Insight di un video Facebook")
    public MetaApiModels.InsightsResponse videoInsights(
            @PathVariable String videoId,
            @RequestParam(required = false) String metrics) {
        return service.videoInsights(videoId, metrics);
    }

    @GetMapping("/top-content")
    @Operation(operationId = "getFacebookTopContent", summary = "Classifica dei migliori contenuti Facebook")
    public MetaApiModels.FacebookContentListResponse topContent(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") FacebookContentType type,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "REACH") MetaSortBy sortBy) {
        return service.topContent(startDate, endDate, type, limit, sortBy);
    }
}
