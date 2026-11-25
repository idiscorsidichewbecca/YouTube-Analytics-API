package it.chewbecca.youtubeanalytics.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.TrendGranularity;
import it.chewbecca.youtubeanalytics.service.YoutubeReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/channel")
@Tag(name = "Channel reports")
public class ChannelReportController {

    private final YoutubeReportService reportService;

    public ChannelReportController(YoutubeReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/summary")
    @Operation(operationId = "getChannelSummary", summary = "Riepilogo del canale e metriche del periodo")
    public ApiModels.ChannelSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.channelSummary(startDate, endDate);
    }

    @GetMapping("/trend")
    @Operation(operationId = "getChannelTrend", summary = "Andamento giornaliero o mensile del canale")
    public ApiModels.TrendResponse trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "DAY") TrendGranularity granularity) {
        return reportService.channelTrend(startDate, endDate, granularity);
    }

    @GetMapping("/subscription-status")
    @Operation(operationId = "getChannelSubscriptionStatus", summary = "Confronta spettatori iscritti e non iscritti")
    public ApiModels.BreakdownResponse subscriptionStatus(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.channelSubscriptionStatus(startDate, endDate);
    }

    @GetMapping("/devices")
    @Operation(operationId = "getChannelDevices", summary = "Distribuzione delle visualizzazioni per dispositivo")
    public ApiModels.BreakdownResponse devices(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.channelDevices(startDate, endDate);
    }

    @GetMapping("/operating-systems")
    @Operation(operationId = "getChannelOperatingSystems", summary = "Distribuzione per sistema operativo")
    public ApiModels.BreakdownResponse operatingSystems(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "25") int limit) {
        return reportService.channelOperatingSystems(startDate, endDate, limit);
    }

    @GetMapping("/demographics")
    @Operation(operationId = "getChannelDemographics", summary = "Distribuzione demografica per età e genere")
    public ApiModels.DemographicsResponse demographics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportService.channelDemographics(startDate, endDate);
    }
}
