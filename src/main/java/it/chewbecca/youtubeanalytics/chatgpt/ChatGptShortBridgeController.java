package it.chewbecca.youtubeanalytics.chatgpt;

import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublicationPlanner;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingController;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Endpoint server-to-server per il solo plugin privato (non è direttamente un endpoint MCP). */
@RestController
@RequestMapping("/internal/chatgpt/shorts")
public class ChatGptShortBridgeController {
    private final ShortPublicationPlanner planner;
    private final ShortPublishingService publishing;
    private final OpenAiFileDownloadService downloader;

    public ChatGptShortBridgeController(ShortPublicationPlanner planner, ShortPublishingService publishing,
                                       OpenAiFileDownloadService downloader) {
        this.planner = planner;
        this.publishing = publishing;
        this.downloader = downloader;
    }

    @GetMapping("/recommendation")
    public ShortPublicationPlanner.Recommendation recommendation() { return planner.recommend(); }

    @PostMapping("/drafts")
    public ShortDraft draft(@Valid @RequestBody DraftFromChat request) {
        return publishing.prepareFromChat(request.brief(), request.title(), request.description(), request.madeForKids());
    }

    @GetMapping("/drafts/{id}")
    public ShortDraft draft(@PathVariable String id) { return publishing.getDraft(id); }

    /** Non invocare per creare la bozza: solo dopo che l'utente ha scritto CONFERMO <draftId>. */
    @PostMapping("/drafts/{id}/schedule")
    public ShortPublishingController.ScheduledResponse schedule(@PathVariable String id,
                                                                @Valid @RequestBody ScheduleFromChat request) {
        if (!(("CONFERMO " + id).equals(request.confirmationText()))) {
            throw new IllegalArgumentException("Conferma esplicita richiesta: CONFERMO <id-bozza>");
        }
        // Validazione prima di scaricare: evita download inutile per una bozza non pubblicabile.
        ShortDraft draft = publishing.getDraft(id);
        if (draft.getStatus() != ShortDraft.Status.DRAFT) {
            throw new IllegalStateException("Bozza non più pubblicabile: " + draft.getStatus());
        }
        Path downloaded = downloader.download(request.file());
        try {
            ShortDraft scheduled = publishing.schedule(id,
                    new TemporaryVideoMultipart(downloaded, "chatgpt-short.mp4"),
                    request.title(), request.description(), request.publishAt());
            return new ShortPublishingController.ScheduledResponse(scheduled.getId(), scheduled.getStatus().name(),
                    scheduled.getYoutubeVideoId(),
                    "https://www.youtube.com/watch?v=" + scheduled.getYoutubeVideoId(), scheduled.getPublishAt());
        } finally {
            try { Files.deleteIfExists(downloaded); } catch (IOException ignored) { }
        }
    }

    public record DraftFromChat(@NotBlank String brief, @NotBlank String title,
                                @NotBlank String description, @NotNull Boolean madeForKids) {}
    public record ScheduleFromChat(@NotNull OpenAiFileDownloadService.FileInput file,
                                   @NotBlank String confirmationText,
                                   String title, String description, Instant publishAt) {}
}
