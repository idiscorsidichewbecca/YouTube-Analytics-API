package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortPublishingServiceScheduledTimeTest {
    final Clock now = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);
    final ShortDraftRepository drafts = mock(ShortDraftRepository.class);
    final ShortPublishingService service = new ShortPublishingService(
            mock(ShortPublicationPlanner.class), mock(ShortMetadataGenerator.class), drafts,
            mock(YouTubeShortUploadClient.class), mock(GoogleAccessTokenService.class),
            mock(TransactionTemplate.class), now, mock(WeeklyShortPlanner.class));

    @Test void userSelectedDateIsPreservedRatherThanRecommendedTime() {
        when(drafts.save(any(ShortDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var wanted = Instant.parse("2026-12-10T18:30:00Z");
        var d = service.prepareFromChatAt(
                "Un video documentario su un veicolo di Star Wars, con varie curiosita' canoniche.",
                "Tre curiosità sul veicolo più famoso", "Il video racconta queste curiosità. #StarWars",
                false, wanted);
        assertThat(d.getPublishAt()).isEqualTo(wanted);
        assertThat(d.getStatus()).isEqualTo(ShortDraft.Status.DRAFT);
        assertThat(d.isWeeklyPlan()).isFalse();
        verify(drafts).save(any(ShortDraft.class));
    }

    @Test void weeklyDraftsExpireAtTheEndOfTheItalianWeek() {
        var brief = "Un video documentario su un veicolo di Star Wars con diverse curiosità.";
        when(drafts.save(any(ShortDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var sunday = Instant.parse("2026-11-29T22:30:00Z");
        var monday = Instant.parse("2026-11-29T23:00:00Z");
        assertThat(service.prepareFromChatAt(brief, "Titolo", "Descrizione", false, sunday, true)
                .isWeeklyPlan()).isTrue();
        assertThatThrownBy(() -> service.prepareFromChatAt(brief, "Titolo", "Descrizione", false, monday, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tooNearAndTooFarDatesAreRejected() {
        var brief = "Un video documentario su un veicolo di Star Wars, con varie curiosita' canoniche.";
        assertThatThrownBy(() -> service.prepareFromChatAt(
                brief, "Titolo", "Descrizione", false, now.instant().plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareFromChatAt(
                brief, "Titolo", "Descrizione", false, now.instant().plusSeconds(366L * 86400)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(drafts);
    }
}
