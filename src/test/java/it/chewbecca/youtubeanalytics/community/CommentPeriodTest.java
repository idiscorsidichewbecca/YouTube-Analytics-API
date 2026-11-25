package it.chewbecca.youtubeanalytics.community;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class CommentPeriodTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void usesMondayInRomeEvenWhileUtcIsSunday() {
        var thisWeek = CommentPeriod.resolve(clock, "THIS_WEEK", null, null, null);
        assertThat(thisWeek.from()).isEqualTo(LocalDate.of(2026, 10, 26));
        var previous = CommentPeriod.resolve(clock, "PREVIOUS_WEEK", null, null, null);
        assertThat(previous.includes(Instant.parse("2026-10-25T22:59:59Z"))).isTrue();
        assertThat(previous.includes(Instant.parse("2026-10-25T23:00:00Z"))).isFalse();
    }

    @Test
    void validatesRangeAndCountsTodayInRollingPeriod() {
        assertThat(CommentPeriod.resolve(clock, "LAST_DAYS", 3, null, null).from())
                .isEqualTo("2026-10-24");
        assertThatIllegalArgumentException().isThrownBy(
                () -> CommentPeriod.resolve(clock, "LAST_DAYS", 0, null, null));
    }
}
