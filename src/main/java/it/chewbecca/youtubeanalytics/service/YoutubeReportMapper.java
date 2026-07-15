package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import org.springframework.stereotype.Component;

@Component
public class YoutubeReportMapper {

    public ApiModels.AnalyticsMetrics metrics(YouTubeAnalyticsClient.AnalyticsMetrics source) {
        return new ApiModels.AnalyticsMetrics(
                source.engagedViews(),
                source.views(),
                source.comments(),
                source.likes(),
                source.shares(),
                source.estimatedMinutesWatched(),
                source.estimatedMinutesWatched() / 60.0,
                source.averageViewDurationSeconds(),
                source.averageViewPercentage(),
                source.subscribersGained(),
                source.subscribersLost(),
                source.netSubscribers(),
                source.engagementRatePercentage()
        );
    }

    public ApiModels.BreakdownItem breakdown(YouTubeAnalyticsClient.BreakdownMetric source) {
        return new ApiModels.BreakdownItem(
                source.dimensionValue(),
                source.engagedViews(),
                source.views(),
                source.estimatedMinutesWatched(),
                source.estimatedMinutesWatched() / 60.0,
                source.averageViewDurationSeconds(),
                source.averageViewPercentage(),
                source.netSubscribers()
        );
    }
}
