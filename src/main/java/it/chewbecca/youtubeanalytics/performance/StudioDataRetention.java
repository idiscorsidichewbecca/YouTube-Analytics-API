package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.community.CommunityReplyRepository;
import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService.ChannelDisconnected;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@EnableScheduling
public class StudioDataRetention {
    private final JdbcTemplate jdbc;
    private final CommunityReplyRepository comments;
    private final ReachRepository reach;
    private final VideoOptimizationRepository optimizations;
    private final Clock clock;

    public StudioDataRetention(JdbcTemplate jdbc, CommunityReplyRepository comments, ReachRepository reach,
                               VideoOptimizationRepository optimizations, Clock clock) {
        this.jdbc = jdbc;
        this.comments = comments;
        this.reach = reach;
        this.optimizations = optimizations;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 4 * * *", zone = "Europe/Rome")
    public void expire() {
        var before = clock.instant().minus(Duration.ofDays(28));
        comments.expire(before);
        reach.expire(before);
        jdbc.update("delete from advisor_end_screen_day where verified_at < ?", ReachRepository.utc(before));
        optimizations.purgeExpiredData(before);
    }

    @EventListener
    public void disconnect(ChannelDisconnected event) {
        jdbc.update("delete from community_reply_batch where channel_id=?", event.channelId());
        jdbc.update("delete from advisor_end_screen_day where channel_id=?", event.channelId());
        jdbc.update("delete from advisor_reach_day where channel_id=?", event.channelId());
        jdbc.update("delete from advisor_optimization where channel_id=?", event.channelId());
    }
}
