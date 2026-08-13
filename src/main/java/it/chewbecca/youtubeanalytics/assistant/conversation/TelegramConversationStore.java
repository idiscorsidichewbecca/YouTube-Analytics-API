package it.chewbecca.youtubeanalytics.assistant.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramConversationStore {

    private final TelegramConversationRepository repository;

    public TelegramConversationStore(TelegramConversationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String previousResponseId(long chatId, long threadId) {
        return repository.findByTelegramChatIdAndTelegramThreadId(chatId, threadId)
                .map(TelegramConversation::getPreviousResponseId)
                .orElse(null);
    }

    @Transactional
    public void saveResponseId(long chatId, long threadId, String responseId) {
        TelegramConversation conversation = repository
                .findByTelegramChatIdAndTelegramThreadId(chatId, threadId)
                .orElseGet(() -> TelegramConversation.create(chatId, threadId));
        conversation.continueFrom(responseId);
        repository.save(conversation);
    }

    @Transactional
    public void reset(long chatId, long threadId) {
        repository.findByTelegramChatIdAndTelegramThreadId(chatId, threadId)
                .ifPresent(TelegramConversation::reset);
    }
}
