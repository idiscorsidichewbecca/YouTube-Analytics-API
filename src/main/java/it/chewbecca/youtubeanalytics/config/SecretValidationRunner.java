package it.chewbecca.youtubeanalytics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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

    public SecretValidationRunner(AppSecurityProperties properties,
                                  GoogleOAuthProperties googleOAuthProperties,
                                  MetaProperties metaProperties) {
        this.properties = properties;
        this.googleOAuthProperties = googleOAuthProperties;
        this.metaProperties = metaProperties;
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

        if (!insecure.isEmpty()) {
            String message = "Configurazione non sicura o incompleta: " + String.join(", ", insecure);
            if (properties.failOnDefaultSecrets()) {
                throw new IllegalStateException(message);
            }
            log.warn("{}. Valori ammessi solo in sviluppo locale.", message);
        }
    }
}
