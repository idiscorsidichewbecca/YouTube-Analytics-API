package it.chewbecca.youtubeanalytics.telegram.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.chewbecca.youtubeanalytics.telegram.client.TelegramClient;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate.TelegramMessage;
import it.chewbecca.youtubeanalytics.assistant.service.ChewbeccaStrategistService;
import it.chewbecca.youtubeanalytics.assistant.client.OpenAiResponsesClient.OpenAiApiException;
import it.chewbecca.youtubeanalytics.assistant.service.ChewbeccaStrategistService.AssistantUnavailableException;
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
    private static final String PING_REPLY = "Bot attivo ✅";

    private final TelegramProperties properties;
    private final TelegramClient telegramClient;
    private final ChewbeccaStrategistService strategistService;
    private final Cache<Long, Boolean> processedUpdates = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public TelegramUpdateService(TelegramProperties properties,
                                 TelegramClient telegramClient,
                                 ChewbeccaStrategistService strategistService) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.strategistService = strategistService;
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
            IncomingCommand command = parseCommand(message);
            if (command == null) {
                return;
            }

            if (command.type() == CommandType.PING) {
                reply(message, PING_REPLY);
            } else if (command.type() == CommandType.RESET) {
                strategistService.reset(message.chat().id(), message.messageThreadId());
                reply(message, "Memoria di Chewbecca-Strategist azzerata per questo topic ✅");
            } else {
                replyFromStrategist(message, command.prompt());
            }
        } catch (RuntimeException ex) {
            processedUpdates.invalidate(update.updateId());
            log.error("Elaborazione update Telegram non riuscita: updateId={}", update.updateId(), ex);
        }
    }

    private void replyFromStrategist(TelegramMessage message, String prompt) {
        try {
            String author = author(message);
            String answer = strategistService.reply(
                    message.chat().id(),
                    message.messageThreadId(),
                    author,
                    prompt
            );
            reply(message, answer);
        } catch (OpenAiApiException | AssistantUnavailableException ex) {
            reply(message, "Non riesco a contattare Chewbecca-Strategist: " + ex.getMessage());
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

    private IncomingCommand parseCommand(TelegramMessage message) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }

        String original = message.text().trim();
        String text = original.toLowerCase(Locale.ROOT);
        if (text.equals("ping") || text.equals("/ping") || commandForThisBot(text, "/ping@")) {
            return new IncomingCommand(CommandType.PING, null);
        }
        if (text.equals("/reset") || commandForThisBot(text, "/reset@")) {
            return new IncomingCommand(CommandType.RESET, null);
        }

        String slashPrompt = promptAfterAskCommand(original);
        if (slashPrompt != null) {
            return slashPrompt.isBlank() ? null : new IncomingCommand(CommandType.PROMPT, slashPrompt);
        }

        String mention = "@" + properties.normalizedBotUsername();
        if (!properties.normalizedBotUsername().isBlank() && text.startsWith(mention)) {
            String prompt = original.substring(mention.length()).trim();
            return prompt.isBlank() ? null : new IncomingCommand(CommandType.PROMPT, prompt);
        }

        if (isReplyToBot(message)) {
            return new IncomingCommand(CommandType.PROMPT, original);
        }

        return null;
    }

    private String promptAfterAskCommand(String original) {
        int separator = original.indexOf(' ');
        String command = separator < 0 ? original : original.substring(0, separator);
        String normalizedCommand = command.toLowerCase(Locale.ROOT);
        if (!normalizedCommand.equals("/ask")
                && !(normalizedCommand.startsWith("/ask@")
                && matchesConfiguredUsername(normalizedCommand.substring("/ask@".length())))) {
            return null;
        }
        return separator < 0 ? "" : original.substring(separator + 1).trim();
    }

    private boolean commandForThisBot(String text, String prefix) {
        if (!text.startsWith(prefix)) {
            return false;
        }
        return matchesConfiguredUsername(text.substring(prefix.length()));
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

    private String author(TelegramMessage message) {
        if (message.from() == null) {
            return "membro del team";
        }
        if (StringUtils.hasText(message.from().username())) {
            return "@" + message.from().username();
        }
        if (StringUtils.hasText(message.from().firstName())) {
            return message.from().firstName();
        }
        return "utente Telegram " + message.from().id();
    }

    private enum CommandType {
        PING, RESET, PROMPT
    }

    private record IncomingCommand(CommandType type, String prompt) {
    }
}
