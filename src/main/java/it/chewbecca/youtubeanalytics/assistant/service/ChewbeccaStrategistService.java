package it.chewbecca.youtubeanalytics.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionCatalog;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionExecutor;
import it.chewbecca.youtubeanalytics.assistant.client.OpenAiResponsesClient;
import it.chewbecca.youtubeanalytics.assistant.client.OpenAiResponsesClient.OpenAiApiException;
import it.chewbecca.youtubeanalytics.assistant.config.AssistantProperties;
import it.chewbecca.youtubeanalytics.assistant.conversation.TelegramConversationStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ChewbeccaStrategistService {

    private final AssistantProperties properties;
    private final OpenAiResponsesClient openAiClient;
    private final AnalyticsActionCatalog actionCatalog;
    private final AnalyticsActionExecutor actionExecutor;
    private final AssistantInstructionsProvider instructionsProvider;
    private final TelegramConversationStore conversationStore;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<ConversationKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ChewbeccaStrategistService(AssistantProperties properties,
                                      OpenAiResponsesClient openAiClient,
                                      AnalyticsActionCatalog actionCatalog,
                                      AnalyticsActionExecutor actionExecutor,
                                      AssistantInstructionsProvider instructionsProvider,
                                      TelegramConversationStore conversationStore,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.openAiClient = openAiClient;
        this.actionCatalog = actionCatalog;
        this.actionExecutor = actionExecutor;
        this.instructionsProvider = instructionsProvider;
        this.conversationStore = conversationStore;
        this.objectMapper = objectMapper;
    }

    public String reply(long chatId, Long messageThreadId, String author, String prompt) {
        if (!properties.enabled()) {
            throw new AssistantUnavailableException("Chewbecca-Strategist non è ancora abilitato su Render");
        }
        long threadId = messageThreadId == null ? 0L : messageThreadId;
        ConversationKey key = new ConversationKey(chatId, threadId);
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            String previousResponseId = conversationStore.previousResponseId(chatId, threadId);
            AssistantResult result;
            try {
                result = run(author, prompt, previousResponseId);
            } catch (OpenAiApiException ex) {
                if (StringUtils.hasText(previousResponseId) && ex.invalidPreviousResponse()) {
                    conversationStore.reset(chatId, threadId);
                    result = run(author, prompt, null);
                } else {
                    throw ex;
                }
            }
            conversationStore.saveResponseId(chatId, threadId, result.responseId());
            return result.text();
        } finally {
            lock.unlock();
        }
    }

    public void reset(long chatId, Long messageThreadId) {
        long threadId = messageThreadId == null ? 0L : messageThreadId;
        ConversationKey key = new ConversationKey(chatId, threadId);
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            conversationStore.reset(chatId, threadId);
        } finally {
            lock.unlock();
        }
    }

    private AssistantResult run(String author, String prompt, String previousResponseId) {
        String userInput = "Messaggio Telegram di " + author + ":\n" + prompt;
        JsonNode response = openAiClient.createResponse(
                instructionsProvider.get(),
                TextNode.valueOf(userInput),
                previousResponseId,
                actionCatalog.tools()
        );

        for (int round = 0; round <= properties.maxToolRounds(); round++) {
            List<FunctionCall> functionCalls = functionCalls(response);
            if (functionCalls.isEmpty()) {
                return new AssistantResult(response.path("id").asText(), extractText(response));
            }
            if (round == properties.maxToolRounds()) {
                throw new AssistantUnavailableException("Chewbecca-Strategist ha richiesto troppe Actions consecutive");
            }

            ArrayNode toolOutputs = objectMapper.createArrayNode();
            for (FunctionCall call : functionCalls) {
                String output;
                try {
                    output = actionExecutor.execute(call.name(), call.arguments());
                } catch (IllegalArgumentException ex) {
                    output = actionError(ex.getMessage());
                }
                ObjectNode toolOutput = objectMapper.createObjectNode();
                toolOutput.put("type", "function_call_output");
                toolOutput.put("call_id", call.callId());
                toolOutput.put("output", output);
                toolOutputs.add(toolOutput);
            }

            response = openAiClient.createResponse(
                    instructionsProvider.get(),
                    toolOutputs,
                    response.path("id").asText(),
                    actionCatalog.tools()
            );
        }
        throw new AssistantUnavailableException("Chewbecca-Strategist non ha prodotto una risposta");
    }

    private String actionError(String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("actionError", true);
        error.put("message", StringUtils.hasText(message)
                ? message
                : "Action analytics non eseguibile");
        return error.toString();
    }

    private List<FunctionCall> functionCalls(JsonNode response) {
        List<FunctionCall> calls = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            if ("function_call".equals(item.path("type").asText())) {
                calls.add(new FunctionCall(
                        item.path("call_id").asText(),
                        item.path("name").asText(),
                        item.path("arguments").asText("{}")
                ));
            }
        }
        return calls;
    }

    private String extractText(JsonNode response) {
        List<String> parts = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText()) && content.hasNonNull("text")) {
                    parts.add(content.path("text").asText());
                } else if ("refusal".equals(content.path("type").asText()) && content.hasNonNull("refusal")) {
                    parts.add(content.path("refusal").asText());
                }
            }
        }
        String text = String.join("\n", parts).trim();
        if (text.isBlank()) {
            throw new AssistantUnavailableException("OpenAI non ha restituito testo utilizzabile");
        }
        return text;
    }

    private record ConversationKey(long chatId, long threadId) {
    }

    private record FunctionCall(String callId, String name, String arguments) {
    }

    private record AssistantResult(String responseId, String text) {
    }

    public static class AssistantUnavailableException extends RuntimeException {
        public AssistantUnavailableException(String message) {
            super(message);
        }
    }
}
