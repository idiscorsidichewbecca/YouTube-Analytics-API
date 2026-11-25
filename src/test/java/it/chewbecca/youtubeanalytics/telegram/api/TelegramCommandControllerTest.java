package it.chewbecca.youtubeanalytics.telegram.api;

import it.chewbecca.youtubeanalytics.telegram.service.TelegramCommandCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramCommandControllerTest {

    @Test
    void exposesOnlyTheImplementedNoAiCommands() {
        TelegramCommandController controller =
                new TelegramCommandController(new TelegramCommandCatalog());

        TelegramCommandCatalog.CommandsResponse response = controller.commands();

        assertThat(response.mode()).isEqualTo("DETERMINISTIC_NO_AI");
        assertThat(response.usesArtificialIntelligence()).isFalse();
        assertThat(response.commands()).hasSize(11);
        assertThat(response.commands())
                .extracting(TelegramCommandCatalog.CommandDescriptor::command)
                .contains("/topvideo", "/topshort", "/adesso", "/trimestre",
                        "/guadagni30", "/ripartenze", "/confrontomese",
                        "/confrontoanno", "/ultimovideo");
    }
}
