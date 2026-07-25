package it.chewbecca.youtubeanalytics.meta.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.service.MetaOverviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta overview")
public class MetaOverviewController {

    private final MetaOverviewService overviewService;

    public MetaOverviewController(MetaOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    @Operation(operationId = "getMetaOverview", summary = "Riepilogo combinato Facebook e Instagram")
    public MetaApiModels.MetaOverview overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return overviewService.overview(startDate, endDate);
    }
}
