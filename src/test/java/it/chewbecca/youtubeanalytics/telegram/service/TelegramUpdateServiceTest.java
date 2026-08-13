package it.chewbecca.youtubeanalytics.telegram.service;

import it.chewbecca.youtubeanalytics.telegram.client.TelegramClient;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate.TelegramChat;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate.TelegramMessage;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate.TelegramUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TelegramUpdateServiceTest {

    private static final long ALLOWED_CHAT_ID = -1001234567890L;
    private TelegramClient telegramClient;
    private TelegramAnalyticsService analyticsService;
    private TelegramUpdateService service;

    @BeforeEach
    void setUp() {
        TelegramProperties properties = new TelegramProperties(
                true,
                "bot-token",
                "chewbecca_strategist_bot",
                "valid_webhook_secret_123",
                ALLOWED_CHAT_ID
        );
        telegramClient = mock(TelegramClient.class);
        analyticsService = mock(TelegramAnalyticsService.class);
        service = new TelegramUpdateService(properties, telegramClient, analyticsService);
    }

    @Test
    void repliesToMentionedPingInTheSameTopic() {
        TelegramUpdate update = update(1L, ALLOWED_CHAT_ID, 77L, 900L,
                "@chewbecca_strategist_bot ping");
        org.mockito.Mockito.when(analyticsService.answer(
                        "ping", "chewbecca_strategist_bot"))
                .thenReturn("Bot attivo ✅");

        service.process(update);

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, 77L, 900L, "Bot attivo ✅");
    }

    @Test
    void repliesToTelegramCommandWithBotUsername() {
        TelegramUpdate update = update(2L, ALLOWED_CHAT_ID, 88L, 901L,
                "/ping@chewbecca_strategist_bot");
        org.mockito.Mockito.when(analyticsService.answer(
                        "/ping", "chewbecca_strategist_bot"))
                .thenReturn("Bot attivo ✅");

        service.process(update);

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, 88L, 901L, "Bot attivo ✅");
    }

    @Test
    void ignoresUnauthorizedChat() {
        TelegramUpdate update = update(3L, -1009999999999L, 77L, 902L, "/ping");

        service.process(update);

        verify(telegramClient, never()).sendMessage(
                -1009999999999L, 77L, 902L, "Bot attivo ✅");
    }

    @Test
    void ignoresDuplicateTelegramUpdate() {
        TelegramUpdate update = update(4L, ALLOWED_CHAT_ID, 77L, 903L, "/ping");
        org.mockito.Mockito.when(analyticsService.answer(
                        "/ping", "chewbecca_strategist_bot"))
                .thenReturn("Bot attivo ✅");

        service.process(update);
        service.process(update);

        verify(telegramClient).sendMessage(ALLOWED_CHAT_ID, 77L, 903L, "Bot attivo ✅");
    }

    @Test
    void ignoresMentionOfAnotherBot() {
        TelegramUpdate update = update(5L, ALLOWED_CHAT_ID, 77L, 904L, "@another_bot ping");

        service.process(update);

        verify(telegramClient, never()).sendMessage(ALLOWED_CHAT_ID, 77L, 904L, "Bot attivo ✅");
    }

    @Test
    void sendsMentionedNaturalLanguageRequestToDeterministicAnalytics() {
        TelegramUpdate update = update(6L, ALLOWED_CHAT_ID, 91L, 905L,
                "@chewbecca_strategist_bot dimmi i top video");
        org.mockito.Mockito.when(analyticsService.answer(
                        "dimmi i top video", "chewbecca_strategist_bot"))
                .thenReturn("Top video per visualizzazioni...");

        service.process(update);

        verify(analyticsService).answer(
                "dimmi i top video", "chewbecca_strategist_bot");
        verify(telegramClient).sendMessage(
                ALLOWED_CHAT_ID, 91L, 905L, "Top video per visualizzazioni...");
    }

    @Test
    void sendsCommandToDeterministicAnalyticsWithPrivacyModeEnabled() {
        TelegramUpdate update = update(8L, ALLOWED_CHAT_ID, 93L, 907L,
                "/confrontomese@chewbecca_strategist_bot 2025-07");
        org.mockito.Mockito.when(analyticsService.answer(
                        "/confrontomese 2025-07", "chewbecca_strategist_bot"))
                .thenReturn("Confronto mensile...");

        service.process(update);

        verify(analyticsService).answer(
                "/confrontomese 2025-07", "chewbecca_strategist_bot");
        verify(telegramClient).sendMessage(
                ALLOWED_CHAT_ID, 93L, 907L, "Confronto mensile...");
    }

    @Test
    void returnsHelpInTheCurrentTopic() {
        TelegramUpdate update = update(7L, ALLOWED_CHAT_ID, 92L, 906L,
                "/help@chewbecca_strategist_bot");
        org.mockito.Mockito.when(analyticsService.answer(
                        "/help", "chewbecca_strategist_bot"))
                .thenReturn("Comandi disponibili...");

        service.process(update);

        verify(analyticsService).answer("/help", "chewbecca_strategist_bot");
        verify(telegramClient).sendMessage(
                ALLOWED_CHAT_ID, 92L, 906L, "Comandi disponibili...");
    }

    private TelegramUpdate update(long updateId, long chatId, Long threadId, long messageId, String text) {
        TelegramMessage message = new TelegramMessage(
                messageId,
                threadId,
                threadId != null,
                new TelegramChat(chatId, "supergroup", "Team", true),
                new TelegramUser(123L, false, "simone", "Simone"),
                text,
                null
        );
        return new TelegramUpdate(updateId, message);
    }
}
