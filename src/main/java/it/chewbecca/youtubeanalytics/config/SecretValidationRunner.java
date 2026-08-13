package it.chewbecca.youtubeanalytics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.assistant.config.AssistantProperties;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class SecretValidationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretValidationRunner.class);
    private static final String DEFAULT_ADMIN_PASSWORD = "change-me-admin";
    private static final String DEFAULT_GPT_KEY = "change-me-gpt-key";
    private static final String DEFAULT_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AppSecurityProperties properties;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final MetaProperties metaProperties;
    private final TelegramProperties telegramProperties;
    private final AssistantProperties assistantProperties;

    public SecretValidationRunner(AppSecurityProperties properties,
                                  GoogleOAuthProperties googleOAuthProperties,
                                  MetaProperties metaProperties,
                                  TelegramProperties telegramProperties,
                                  AssistantProperties assistantProperties) {
        this.properties = properties;
        this.googleOAuthProperties = googleOAuthProperties;
        this.metaProperties = metaProperties;
        this.telegramProperties = telegramProperties;
        this.assistantProperties = assistantProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> insecure = new ArrayList<>();
        if (DEFAULT_ADMIN_PASSWORD.equals(properties.adminPassword())) {
            insecure.add("ADMIN_PASSWORD");
        }
        if (DEFAULT_GPT_KEY.equals(properties.gptApiKey())) {
            insecure.add("GPT_ACTION_API_KEY");
        }
        if (DEFAULT_ENCRYPTION_KEY.equals(properties.tokenEncryptionKey())) {
            insecure.add("TOKEN_ENCRYPTION_KEY");
        }
        if ("replace-me".equals(googleOAuthProperties.clientId()) || "replace-me".equals(googleOAuthProperties.clientSecret())) {
            insecure.add("GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET");
        }
        if (metaProperties.enabled()) {
            try {
                metaProperties.requireConfigured();
            } catch (IllegalStateException ex) {
                insecure.add("META_APP_ID/META_APP_SECRET/META_CONFIG_ID/META_REDIRECT_URI");
            }
        }
        if (telegramProperties.enabled()) {
            if (!StringUtils.hasText(telegramProperties.botToken())) {
                insecure.add("TELEGRAM_BOT_TOKEN");
            }
            if (!StringUtils.hasText(telegramProperties.webhookSecret())
                    || !telegramProperties.webhookSecret().matches("[A-Za-z0-9_-]{16,256}")) {
                insecure.add("TELEGRAM_WEBHOOK_SECRET (16-256 caratteri: A-Z, a-z, 0-9, _ e -)");
            }
            if (telegramProperties.allowedChatId() == 0L) {
                insecure.add("TELEGRAM_ALLOWED_CHAT_ID");
            }
            if (!StringUtils.hasText(telegramProperties.botUsername())) {
                insecure.add("TELEGRAM_BOT_USERNAME");
            }
        }
        if (assistantProperties.enabled()) {
            if (!StringUtils.hasText(assistantProperties.apiKey())) {
                insecure.add("OPENAI_API_KEY");
            }
            if (!StringUtils.hasText(assistantProperties.model())) {
                insecure.add("OPENAI_MODEL");
            }
            if (assistantProperties.maxOutputTokens() < 256 || assistantProperties.maxToolRounds() < 1) {
                insecure.add("OPENAI_MAX_OUTPUT_TOKENS/OPENAI_MAX_TOOL_ROUNDS");
            }
        }

        if (!insecure.isEmpty()) {
            String message = "Configurazione non sicura o incompleta: " + String.join(", ", insecure);
            if (properties.failOnDefaultSecrets()) {
                throw new IllegalStateException(message);
            }
            log.warn("{}. Valori ammessi solo in sviluppo locale.", message);
        }
    }
}
