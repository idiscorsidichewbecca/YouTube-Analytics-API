package it.chewbecca.youtubeanalytics.assistant.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramConversationRepository extends JpaRepository<TelegramConversation, Long> {
    Optional<TelegramConversation> findByTelegramChatIdAndTelegramThreadId(long chatId, long threadId);
}
