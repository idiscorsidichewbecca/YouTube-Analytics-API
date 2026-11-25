package it.chewbecca.youtubeanalytics.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionCatalog;
import it.chewbecca.youtubeanalytics.assistant.action.AnalyticsActionExecutor;
import it.chewbecca.youtubeanalytics.assistant.client.OpenAiResponsesClient;
import it.chewbecca.youtubeanalytics.assistant.config.AssistantProperties;
import it.chewbecca.youtubeanalytics.assistant.conversation.TelegramConversationStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChewbeccaStrategistServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesRequestedAnalyticsActionAndReturnsModelAnalysis() throws Exception {
        AssistantProperties properties = new AssistantProperties(
                true, "openai-key", "gpt-5.6-luna", "", 3000, 6);
        OpenAiResponsesClient openAiClient = mock(OpenAiResponsesClient.class);
        AnalyticsActionCatalog catalog = mock(AnalyticsActionCatalog.class);
        AnalyticsActionExecutor executor = mock(AnalyticsActionExecutor.class);
        AssistantInstructionsProvider instructions = mock(AssistantInstructionsProvider.class);
        TelegramConversationStore store = mock(TelegramConversationStore.class);

        when(catalog.tools()).thenReturn(objectMapper.createArrayNode());
        when(instructions.get()).thenReturn("Istruzioni");
        when(store.previousResponseId(-1001L, 77L)).thenReturn(null);
        when(openAiClient.createResponse(eq("Istruzioni"), any(), isNull(), any()))
                .thenReturn(json("""
                        {
                          "id":"resp_1",
                          "output":[{
                            "type":"function_call",
                            "call_id":"call_1",
                            "name":"getChannelSummary",
                            "arguments":"{\\"startDate\\":\\"2026-07-01\\",\\"endDate\\":\\"2026-07-31\\"}"
                          }]
                        }
                        """));
        when(executor.execute("getChannelSummary",
                "{\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-31\"}"))
                .thenReturn("{\"views\":1000}");
        when(openAiClient.createResponse(eq("Istruzioni"), any(), eq("resp_1"), any()))
                .thenReturn(json("""
                        {
                          "id":"resp_2",
                          "output":[{
                            "type":"message",
                            "content":[{"type":"output_text","text":"A luglio il canale ha ottenuto 1.000 visualizzazioni."}]
                          }]
                        }
                        """));

        ChewbeccaStrategistService service = new ChewbeccaStrategistService(
                properties, openAiClient, catalog, executor, instructions, store, objectMapper);

        String answer = service.reply(-1001L, 77L, "@simone", "Analizza luglio");

        assertThat(answer).isEqualTo("A luglio il canale ha ottenuto 1.000 visualizzazioni.");
        verify(executor).execute("getChannelSummary",
                "{\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-31\"}");
        verify(store).saveResponseId(-1001L, 77L, "resp_2");
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
