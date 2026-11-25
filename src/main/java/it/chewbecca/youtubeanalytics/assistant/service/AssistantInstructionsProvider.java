package it.chewbecca.youtubeanalytics.assistant.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AssistantInstructionsProvider {

    private static final String RESOURCE = "assistant/chewbecca-strategist-instructions.txt";
    private final String instructions;

    public AssistantInstructionsProvider() {
        try {
            instructions = new ClassPathResource(RESOURCE)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossibile caricare le istruzioni di Chewbecca-Strategist", ex);
        }
    }

    public String get() {
        return instructions;
    }
}
