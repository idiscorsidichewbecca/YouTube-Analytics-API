package it.chewbecca.youtubeanalytics.telegram.client;

import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramSendMessageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TelegramClient {

    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final int SAFE_TEXT_LIMIT = 3900;

    private final TelegramProperties properties;
    private final RestClient restClient;

    public TelegramClient(TelegramProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl("https://api.telegram.org").build();
    }

    public void sendMessage(long chatId, Long messageThreadId, Long replyToMessageId, String text) {
        boolean firstChunk = true;
        for (String chunk : split(text)) {
            sendSingleMessage(chatId, messageThreadId, firstChunk ? replyToMessageId : null, chunk);
            firstChunk = false;
        }
    }

    private void sendSingleMessage(long chatId, Long messageThreadId, Long replyToMessageId, String text) {
        TelegramSendMessageRequest request = new TelegramSendMessageRequest(
                chatId,
                messageThreadId,
                text,
                replyToMessageId == null
                        ? null
                        : new TelegramSendMessageRequest.ReplyParameters(replyToMessageId, true)
        );

        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // Non propagare il messaggio originale: l'URI dell'errore potrebbe contenere il bot token.
            throw new TelegramDeliveryException("Telegram sendMessage non riuscito");
        }
    }

    private java.util.List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of("Chewbecca-Strategist non ha prodotto una risposta.");
        }
        if (text.length() <= TELEGRAM_TEXT_LIMIT) {
            return java.util.List.of(text);
        }

        java.util.List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + SAFE_TEXT_LIMIT, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                int space = text.lastIndexOf(' ', end);
                int preferred = Math.max(newline, space);
                if (preferred > start + SAFE_TEXT_LIMIT / 2) {
                    end = preferred;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    public static class TelegramDeliveryException extends RuntimeException {
        public TelegramDeliveryException(String message) {
            super(message);
        }
    }
}
