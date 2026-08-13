package it.chewbecca.youtubeanalytics.telegram.api;

import it.chewbecca.youtubeanalytics.telegram.service.TelegramCommandCatalog;
import it.chewbecca.youtubeanalytics.telegram.service.TelegramCommandCatalog.CommandsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/telegram")
public class TelegramCommandController {

    private final TelegramCommandCatalog commandCatalog;

    public TelegramCommandController(TelegramCommandCatalog commandCatalog) {
        this.commandCatalog = commandCatalog;
    }

    @GetMapping("/commands")
    public CommandsResponse commands() {
        return commandCatalog.response();
    }
}
