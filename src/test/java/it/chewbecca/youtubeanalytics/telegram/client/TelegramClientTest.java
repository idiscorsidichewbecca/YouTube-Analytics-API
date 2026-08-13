package it.chewbecca.youtubeanalytics.telegram.client;

import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class TelegramClientTest {

    @Test
    void sendsMessageToTheRequestedTelegramTopic() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramProperties properties = new TelegramProperties(
                true,
                "test-token",
                "chewbecca_strategist_bot",
                "valid_webhook_secret_123",
                -1001234567890L
        );
        TelegramClient client = new TelegramClient(properties, builder);

        server.expect(once(), requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "chat_id": -1001234567890,
                          "message_thread_id": 77,
                          "text": "Bot attivo ✅",
                          "reply_parameters": {
                            "message_id": 900,
                            "allow_sending_without_reply": true
                          }
                        }
                        """))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        client.sendMessage(-1001234567890L, 77L, 900L, "Bot attivo ✅");

        server.verify();
    }
}
