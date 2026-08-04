package it.chewbecca.youtubeanalytics.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YouTubeRevenueMetricsTest {

    @Test
    void calculatesEstimatedRpmAndMonetizedPlaybackRate() {
        YouTubeAnalyticsClient.RevenueMetrics metrics = new YouTubeAnalyticsClient.RevenueMetrics(
                20_000,
                50.0,
                42.0,
                8.0,
                90.0,
                5_000,
                8_000,
                18.0,
                11.25
        );

        assertEquals(2.5, metrics.estimatedRpm(), 0.0001);
        assertEquals(25.0, metrics.monetizedPlaybackRatePercentage(), 0.0001);
    }

    @Test
    void returnsZeroRatesWhenThereAreNoViews() {
        YouTubeAnalyticsClient.RevenueMetrics metrics = YouTubeAnalyticsClient.RevenueMetrics.empty();

        assertEquals(0.0, metrics.estimatedRpm());
        assertEquals(0.0, metrics.monetizedPlaybackRatePercentage());
    }
}
