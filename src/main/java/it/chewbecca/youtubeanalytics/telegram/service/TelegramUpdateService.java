package it.chewbecca.youtubeanalytics.telegram.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.chewbecca.youtubeanalytics.telegram.client.TelegramClient;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate.TelegramMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

@Service
public class TelegramUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateService.class);
    private final TelegramProperties properties;
    private final TelegramClient telegramClient;
    private final TelegramAnalyticsService analyticsService;
    private final Cache<Long, Boolean> processedUpdates = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public TelegramUpdateService(TelegramProperties properties,
                                 TelegramClient telegramClient,
                                 TelegramAnalyticsService analyticsService) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.analyticsService = analyticsService;
    }

    @Async
    public void process(TelegramUpdate update) {
        if (!properties.enabled() || update == null || update.updateId() == null || update.message() == null) {
            return;
        }
        if (processedUpdates.asMap().putIfAbsent(update.updateId(), Boolean.TRUE) != null) {
            return;
        }

        try {
            TelegramMessage message = update.message();
            if (message.chat() == null || message.chat().id() == null) {
                return;
            }
            if (message.chat().id() != properties.allowedChatId()) {
                log.warn("Update Telegram ignorato da chat non autorizzata: chatId={} threadId={}",
                        message.chat().id(), message.messageThreadId());
                return;
            }
            if (message.from() != null && Boolean.TRUE.equals(message.from().bot())) {
                return;
            }
            String input = extractInput(message);
            if (input == null) {
                return;
            }
            replyFromAnalytics(message, input);
        } catch (RuntimeException ex) {
            processedUpdates.invalidate(update.updateId());
            log.error("Elaborazione update Telegram non riuscita: updateId={}", update.updateId(), ex);
        }
    }

    private void replyFromAnalytics(TelegramMessage message, String input) {
        try {
            String answer = analyticsService.answer(input, properties.normalizedBotUsername());
            reply(message, answer);
        } catch (RuntimeException ex) {
            log.warn("Comando analytics Telegram non riuscito: tipo={}", ex.getClass().getSimpleName());
            reply(message, "Non riesco a recuperare i dati analytics in questo momento. "
                    + "Controlla il collegamento YouTube e riprova.");
        }
    }

    private void reply(TelegramMessage message, String text) {
        telegramClient.sendMessage(
                message.chat().id(),
                message.messageThreadId(),
                message.messageId(),
                text
        );
    }

    private String extractInput(TelegramMessage message) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }

        String original = message.text().trim();
        String text = original.toLowerCase(Locale.ROOT);
        if (original.startsWith("/")) {
            int separator = original.indexOf(' ');
            String commandToken = separator < 0 ? original : original.substring(0, separator);
            int mentionSeparator = commandToken.indexOf('@');
            if (mentionSeparator >= 0) {
                String recipient = commandToken.substring(mentionSeparator + 1);
                if (!matchesConfiguredUsername(recipient)) {
                    return null;
                }
                String canonicalCommand = commandToken.substring(0, mentionSeparator);
                return separator < 0
                        ? canonicalCommand
                        : canonicalCommand + original.substring(separator);
            }
            return original;
        }

        String mention = "@" + properties.normalizedBotUsername();
        if (!properties.normalizedBotUsername().isBlank() && text.startsWith(mention)) {
            String prompt = original.substring(mention.length()).trim();
            return prompt.isBlank() ? "/help" : prompt;
        }

        if (isReplyToBot(message)) {
            return original;
        }

        if (text.equals("ping")) {
            return "ping";
        }
        return null;
    }

    private boolean matchesConfiguredUsername(String candidate) {
        String configured = properties.normalizedBotUsername();
        return !configured.isBlank() && configured.equals(candidate.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isReplyToBot(TelegramMessage message) {
        TelegramMessage repliedTo = message.replyToMessage();
        return repliedTo != null
                && repliedTo.from() != null
                && Boolean.TRUE.equals(repliedTo.from().bot())
                && matchesConfiguredUsername(repliedTo.from().username());
    }

}
