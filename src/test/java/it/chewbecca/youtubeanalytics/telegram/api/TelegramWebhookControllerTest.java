package it.chewbecca.youtubeanalytics.telegram.api;

import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate;
import it.chewbecca.youtubeanalytics.telegram.service.TelegramUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TelegramWebhookControllerTest {

    private static final String SECRET = "valid_webhook_secret_123";

    @Test
    void acceptsUpdateWithValidTelegramSecret() {
        TelegramUpdateService service = mock(TelegramUpdateService.class);
        TelegramWebhookController controller = new TelegramWebhookController(properties(true), service);
        TelegramUpdate update = new TelegramUpdate(1L, null);

        ResponseEntity<Void> response = controller.receive(SECRET, update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).process(update);
    }

    @Test
    void rejectsUpdateWithInvalidTelegramSecret() {
        TelegramUpdateService service = mock(TelegramUpdateService.class);
        TelegramWebhookController controller = new TelegramWebhookController(properties(true), service);
        TelegramUpdate update = new TelegramUpdate(2L, null);

        ResponseEntity<Void> response = controller.receive("wrong-secret", update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).process(update);
    }

    @Test
    void hidesWebhookWhenTelegramIntegrationIsDisabled() {
        TelegramUpdateService service = mock(TelegramUpdateService.class);
        TelegramWebhookController controller = new TelegramWebhookController(properties(false), service);
        TelegramUpdate update = new TelegramUpdate(3L, null);

        ResponseEntity<Void> response = controller.receive(SECRET, update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).process(update);
    }

    private TelegramProperties properties(boolean enabled) {
        return new TelegramProperties(
                enabled,
                "bot-token",
                "chewbecca_strategist_bot",
                SECRET,
                -1001234567890L
        );
    }
}
