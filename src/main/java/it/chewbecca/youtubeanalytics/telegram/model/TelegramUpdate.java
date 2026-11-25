package it.chewbecca.youtubeanalytics.telegram.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        @JsonProperty("update_id") Long updateId,
        TelegramMessage message
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramMessage(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("message_thread_id") Long messageThreadId,
            @JsonProperty("is_topic_message") Boolean topicMessage,
            TelegramChat chat,
            TelegramUser from,
            String text,
            @JsonProperty("reply_to_message") TelegramMessage replyToMessage
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChat(
            Long id,
            String type,
            String title,
            @JsonProperty("is_forum") Boolean forum
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramUser(
            Long id,
            @JsonProperty("is_bot") Boolean bot,
            String username,
            @JsonProperty("first_name") String firstName
    ) {
    }
}
