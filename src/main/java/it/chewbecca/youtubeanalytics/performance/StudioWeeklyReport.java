package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.community.CommentPeriod;
import it.chewbecca.youtubeanalytics.community.CommunityManager;
import it.chewbecca.youtubeanalytics.exception.YoutubeNotConnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StudioWeeklyReport {
    private static final Logger log = LoggerFactory.getLogger(StudioWeeklyReport.class);
    private final PerformanceAdvisor advisor;
    private final VideoOptimizationService optimizations;
    private final CommunityManager comments;
    private final ObjectProvider<JavaMailSender> mail;
    private final Clock clock;
    private final boolean withCommunity;
    private final boolean enabled;
    private final boolean evaluationsEnabled;
    private final boolean emailEnabled;
    private final String from;
    private final String to;

    public StudioWeeklyReport(PerformanceAdvisor advisor, VideoOptimizationService optimizations,
                              CommunityManager comments, ObjectProvider<JavaMailSender> mail, Clock clock,
                              @Value("${app.community.enabled:false}") boolean withCommunity,
                              @Value("${app.performance.enabled:false}") boolean enabled,
                              @Value("${app.performance.evaluations-enabled:false}") boolean evaluationsEnabled,
                              @Value("${app.performance.email-enabled:false}") boolean emailEnabled,
                              @Value("${app.performance.email-from:}") String from,
                              @Value("${app.performance.email-to:}") String to) {
        this.advisor = advisor;
        this.optimizations = optimizations;
        this.comments = comments;
        this.mail = mail;
        this.clock = clock;
        this.withCommunity = withCommunity;
        this.enabled = enabled;
        this.evaluationsEnabled = evaluationsEnabled;
        this.emailEnabled = emailEnabled;
        this.from = from;
        this.to = to;
    }

    public Report report(int commentPages) {
        if (commentPages < 0 || commentPages > 25) throw new IllegalArgumentException("Pagine commenti da 0 a 25");
        var week = CommentPeriod.resolve(clock, "PREVIOUS_WEEK", null, null, null);
        var videos = advisor.audit("VIDEO", week.to().minusDays(83), week.to(), 1, 20, optimizations.cooling());
        var shorts = advisor.audit("SHORT", week.to().minusDays(83), week.to(), 1, 20, optimizations.cooling());
        var highlights = new ArrayList<PerformanceAdvisor.Ranked>();
        highlights.addAll(videos.videos());
        highlights.addAll(shorts.videos());
        var best = advisor.leaders(week.from(), week.to(), 5);

        String cursor = null;
        int checked = 0;
        int pages = 0;
        var pendingRoots = new java.util.HashSet<String>();
        if (withCommunity) {
            do {
                if (pages >= commentPages) break;
                var result = comments.list("PREVIOUS_WEEK", null, null, null, cursor, 20, "published");
                pages++;
                checked += result.comments().size();
                result.comments().stream().filter(row -> row.canReply() && !row.hasChannelReply())
                        .forEach(row -> pendingRoots.add(row.replyParentId()));
                cursor = result.nextPageToken();
            } while (cursor != null && !cursor.isBlank());
        }
        return new Report(week.from(), week.to(), checked, pendingRoots.size(),
                withCommunity && pages > 0 && cursor == null, cursor,
                withCommunity ? comments.insights() : List.of(), best,
                highlights.stream().filter(r -> r.priorityScore() > 0)
                        .sorted(java.util.Comparator.comparingDouble(PerformanceAdvisor.Ranked::priorityScore).reversed())
                        .limit(10).toList(), optimizations.dueEvaluations(false),
                "Interpreta i dati in ChatGPT. Approfondisci CTR, retention e sorgenti prima di proporre un singolo intervento.");
    }

    @Scheduled(cron = "${app.performance.evaluations-cron:0 10 6 * * *}", zone = "Europe/Rome")
    public void evaluateDue() {
        if (!enabled || !evaluationsEnabled) return;
        try {
            optimizations.dueEvaluations(false);
        } catch (YoutubeNotConnectedException ignored) {
            log.info("studio_evaluation outcome=NOT_CONNECTED");
        } catch (RuntimeException error) {
            log.warn("studio_evaluation outcome=ERROR exception={}", error.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.performance.email-cron:0 30 8 * * MON}", zone = "Europe/Rome")
    public void email() {
        if (!enabled || !emailEnabled) return;
        var sender = mail.getIfAvailable();
        if (sender == null || from.isBlank() || to.isBlank()) {
            log.warn("studio_weekly_email outcome=MISSING_SMTP_CONFIGURATION");
            return;
        }
        try {
            var report = report(5);
            var summary = new StringBuilder().append("Periodo ").append(report.from()).append(" – ").append(report.to())
                    .append("\nThread potenzialmente in attesa di risposta: ").append(report.pendingThreads())
                    .append(report.commentScanComplete() ? "\n" : " (scansione parziale o disabilitata)\n")
                    .append("\nVideo con più visualizzazioni nel periodo disponibile\n");
            for (var video : report.highlights()) {
                summary.append(video.video().title()).append(" – ").append(video.views()).append(" views\n");
            }
            summary.append("\nVideo da approfondire\n");
            for (var row : report.opportunities()) {
                summary.append(row.video().title()).append(" – https://www.youtube.com/watch?v=")
                        .append(row.video().videoId()).append("\n");
            }
            summary.append("\nVerifiche post-intervento\n");
            for (var item : report.evaluations()) {
                summary.append(item.id()).append(" – ").append(item.days()).append(" giorni – ")
                        .append(item.status()).append("\n");
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject("Chewbecca Studio – report settimanale");
            message.setText(summary.toString());
            sender.send(message);
            log.info("studio_weekly_email outcome=SENT");
        } catch (RuntimeException error) {
            log.warn("studio_weekly_email outcome=ERROR exception={}", error.getClass().getSimpleName());
        }
    }

    public record Report(LocalDate from, LocalDate to, int observedComments, int pendingThreads,
                         boolean commentScanComplete, String nextCommentPageToken, List<Map<String, Object>> batchCounts,
                         List<PerformanceAdvisor.WeeklyLeader> highlights, List<PerformanceAdvisor.Ranked> opportunities,
                         List<VideoOptimizationService.Evaluation> evaluations, String note) {}
}
