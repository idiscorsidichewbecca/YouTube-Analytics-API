package it.chewbecca.youtubeanalytics.publishing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@RestController
@RequestMapping("/admin/youtube/shorts")
public class ShortPublishingController {
    private final ShortPublicationPlanner planner;
    private final ShortPublishingService publishing;

    public ShortPublishingController(ShortPublicationPlanner planner, ShortPublishingService publishing) {
        this.planner = planner;
        this.publishing = publishing;
    }

    @GetMapping("/recommendation")
    public ShortPublicationPlanner.Recommendation recommendation() {
        return planner.recommend();
    }

    @PostMapping("/drafts")
    public ShortDraft draft(@Valid @RequestBody DraftRequest request) {
        return publishing.prepare(request.brief(), request.madeForKids());
    }

    @GetMapping("/drafts/{id}")
    public ShortDraft draft(@PathVariable String id) {
        return publishing.getDraft(id);
    }

    @PostMapping(value = "/drafts/{id}/schedule", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScheduledResponse schedule(@PathVariable String id,
                                      @RequestPart("video") MultipartFile video,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                      Instant publishAt) {
        ShortDraft published = publishing.schedule(id, video, title, description, publishAt);
        return new ScheduledResponse(published.getId(), published.getStatus().name(),
                published.getYoutubeVideoId(),
                "https://www.youtube.com/watch?v=" + published.getYoutubeVideoId(), published.getPublishAt());
    }

    public record DraftRequest(@NotBlank String brief, @NotNull Boolean madeForKids) {}
    public record ScheduledResponse(String draftId, String status, String videoId,
                                    String youtubeUrl, Instant publishAt) {}
}
