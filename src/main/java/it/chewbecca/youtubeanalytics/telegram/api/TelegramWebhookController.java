package it.chewbecca.youtubeanalytics.telegram.api;

import it.chewbecca.youtubeanalytics.telegram.config.TelegramProperties;
import it.chewbecca.youtubeanalytics.telegram.model.TelegramUpdate;
import it.chewbecca.youtubeanalytics.telegram.service.TelegramUpdateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/integrations/telegram")
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramProperties properties;
    private final TelegramUpdateService updateService;

    public TelegramWebhookController(TelegramProperties properties, TelegramUpdateService updateService) {
        this.properties = properties;
        this.updateService = updateService;
    }

    @PostMapping("/webhook")
    ResponseEntity<Void> receive(
            @RequestHeader(value = SECRET_HEADER, required = false) String providedSecret,
            @RequestBody TelegramUpdate update
    ) {
        if (!properties.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!constantTimeEquals(providedSecret, properties.webhookSecret())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        updateService.process(update);
        return ResponseEntity.ok().build();
    }

    private boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8)
        );
    }
}
