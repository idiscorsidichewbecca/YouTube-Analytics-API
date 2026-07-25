package it.chewbecca.youtubeanalytics.meta.service;

import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

@Service
public class MetaOverviewService {

    private final FacebookAnalyticsService facebookService;
    private final InstagramAnalyticsService instagramService;

    public MetaOverviewService(FacebookAnalyticsService facebookService,
                               InstagramAnalyticsService instagramService) {
        this.facebookService = facebookService;
        this.instagramService = instagramService;
    }

    public MetaApiModels.MetaOverview overview(LocalDate startDate, LocalDate endDate) {
        return new MetaApiModels.MetaOverview(
                facebookService.summary(startDate, endDate),
                instagramService.summary(startDate, endDate),
                Instant.now()
        );
    }
}
