package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstagramQuizManualTest {
    private InstagramQuizService service;
    private YouTubeQuizCalendar calendar;
    private Clock clock;
    private TransactionTemplate tx;
    private final Instant start=Instant.parse("2026-11-26T22:28:00Z");
    private final InstagramQuizService.QuizInput entry=new InstagramQuizService.QuizInput(
            "CANON","Quale dettaglio viene rivelato nel romanzo?","Una risposta verificabile sufficientemente lunga.","Romanzo di esempio, capitolo 12");

    @BeforeEach void init(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__instagram_quiz.sql")).execute(ds);
        var graph=mock(InstagramGraphClient.class);
        when(graph.account()).thenReturn("test-account");
        calendar=mock(YouTubeQuizCalendar.class);
        when(calendar.snapshot()).thenReturn(List.of());
        clock=mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(start);
        service=new InstagramQuizService(new JdbcTemplate(ds),graph,clock,new ObjectMapper(),calendar,true);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test void questionOnlyQueuesAtApprovalNotDraftTime(){
        var draft=service.prepareManual(entry,"NOW",null);
        assertThat(draft.quizzes().getFirst().stories()).hasSize(1);
        assertThat(draft.confirmation()).isNull();
        var staged=service.stage(draft.id(),1,new byte[]{1},null);
        assertThat(service.graphics(draft.id(),1)).hasSize(1);
        assertThat(staged.confirmation()).isNotNull();
        when(clock.instant()).thenReturn(start.plusSeconds(90));
        var scheduled=tx.execute(s->service.schedule(draft.id(),staged.confirmation()));
        var story=scheduled.quizzes().getFirst().stories().getFirst();
        assertThat(story.kind()).isEqualTo("QUESTION");
        assertThat(story.status()).isEqualTo("SCHEDULED");
        assertThat(story.publishAt().toInstant()).isEqualTo(start.plusSeconds(90));
    }

    @Test void manualPairRetainsExplicitAnswerTimeAcrossMidnight(){
        var draft=service.prepareManual(entry,"NOW","2026-11-27T10:00:00+01:00");
        assertThatThrownBy(()->service.stage(draft.id(),1,new byte[]{1},null))
                .isInstanceOf(IllegalArgumentException.class);
        var staged=service.stage(draft.id(),1,new byte[]{1},new byte[]{2});
        var scheduled=tx.execute(s->service.schedule(draft.id(),staged.confirmation()));
        assertThat(scheduled.quizzes().getFirst().stories()).hasSize(2);
        assertThat(scheduled.quizzes().getFirst().stories().get(1).publishAt().toInstant())
                .isEqualTo(Instant.parse("2026-11-27T09:00:00Z"));
    }

    @Test void staleApprovalCannotScheduleAndCalendarIsRechecked(){
        var draft=service.prepareManual(entry,"NOW",null);
        var staged=service.stage(draft.id(),1,new byte[]{1},null);
        assertThatThrownBy(()->tx.execute(s->service.schedule(draft.id(),"wrong")))
                .isInstanceOf(IllegalArgumentException.class);
        when(calendar.snapshot()).thenReturn(List.of(new YouTubeStoryWindows.Release("release",start)));
        assertThatThrownBy(()->tx.execute(s->service.schedule(draft.id(),staged.confirmation())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflitto");
        assertThat(service.status(draft.id()).status()).isEqualTo("DRAFT");
    }

    @Test void rejectsInvalidItalianOffsetAndAnswerBeforeQuestion(){
        assertThatThrownBy(()->InstagramQuizService.manualTime("2026-11-27T10:00:00+02:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.prepareManual(entry,"2026-11-27T11:00:00+01:00","2026-11-27T10:00:00+01:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
