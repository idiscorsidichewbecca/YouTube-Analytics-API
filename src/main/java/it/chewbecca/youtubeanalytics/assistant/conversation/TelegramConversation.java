package it.chewbecca.youtubeanalytics.assistant.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "telegram_conversations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_telegram_conversation_topic",
                columnNames = {"telegram_chat_id", "telegram_thread_id"}
        )
)
public class TelegramConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_chat_id", nullable = false)
    private long telegramChatId;

    @Column(name = "telegram_thread_id", nullable = false)
    private long telegramThreadId;

    @Column(name = "openai_previous_response_id", length = 200)
    private String previousResponseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TelegramConversation() {
    }

    public static TelegramConversation create(long chatId, long threadId) {
        TelegramConversation conversation = new TelegramConversation();
        conversation.telegramChatId = chatId;
        conversation.telegramThreadId = threadId;
        conversation.createdAt = Instant.now();
        conversation.updatedAt = conversation.createdAt;
        return conversation;
    }

    public void continueFrom(String responseId) {
        this.previousResponseId = responseId;
        this.updatedAt = Instant.now();
    }

    public void reset() {
        this.previousResponseId = null;
        this.updatedAt = Instant.now();
    }

    public String getPreviousResponseId() {
        return previousResponseId;
    }
}
