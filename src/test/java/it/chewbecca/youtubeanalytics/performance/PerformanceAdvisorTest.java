package it.chewbecca.youtubeanalytics.performance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PerformanceAdvisorTest {
    @Test
    void preservesRetentionRatiosAndReturnsMissingWhenSecondCannotBeObserved() {
        var input = List.<Map<String, Object>>of(
                Map.of("elapsedVideoTimeRatio", 0.48, "audienceWatchRatio", 1.1),
                Map.of("elapsedVideoTimeRatio", 0.5, "audienceWatchRatio", 0.9, "relativeRetentionPerformance", 0.8),
                Map.of("elapsedVideoTimeRatio", 0.52, "audienceWatchRatio", 0.45));
        var retention = PerformanceAdvisor.retention(input, 60);
        assertThat(retention.at30()).isEqualTo(0.9);
        assertThat(retention.at60()).isNull();
        assertThat(retention.largestDrop().drop()).isCloseTo(.45, within(.00001));
        assertThat(retention.replayed()).hasSize(1);
    }

    @Test
    void usesInterpolatedChannelPercentiles() {
        assertThat(PerformanceAdvisor.percentile(List.of(1., 2., 3., 10.), .5)).isEqualTo(2.5);
        assertThat(PerformanceAdvisor.percentile(List.of(1., 2., 3., 10.), .25)).isEqualTo(1.75);
    }
}
