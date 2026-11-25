package it.chewbecca.youtubeanalytics.telegram.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramSendMessageRequest(
        @JsonProperty("chat_id") long chatId,
        @JsonProperty("message_thread_id") Long messageThreadId,
        String text,
        @JsonProperty("reply_parameters") ReplyParameters replyParameters
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplyParameters(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("allow_sending_without_reply") boolean allowSendingWithoutReply
    ) {
    }
}
