package it.chewbecca.youtubeanalytics.publishing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "short_publication_drafts")
public class ShortDraft {
    public enum Status { DRAFT, UPLOADING, SCHEDULED, UNKNOWN }

    @Id
    @Column(length = 36)
    private String id;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 5000)
    private String description;
    @Column(name = "content_brief", nullable = false, length = 10000)
    private String brief;
    @Column(name = "publish_at", nullable = false)
    private Instant publishAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "youtube_video_id", length = 100)
    private String youtubeVideoId;
    @Column(name = "made_for_kids", nullable = false)
    private boolean madeForKids;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
    @Version
    private long version;

    protected ShortDraft() {}

    public static ShortDraft create(String brief, String title, String description, Instant publishAt,
                                    boolean madeForKids, Instant now) {
        ShortDraft draft = new ShortDraft();
        draft.id = UUID.randomUUID().toString();
        draft.brief = brief;
        draft.title = title;
        draft.description = description;
        draft.publishAt = publishAt;
        draft.madeForKids = madeForKids;
        draft.createdAt = now;
        draft.status = Status.DRAFT;
        return draft;
    }

    public void startUpload(String title, String description, Instant publishAt, boolean madeForKids) {
        if (status != Status.DRAFT) throw new IllegalStateException("Bozza già inviata o con esito incerto");
        this.title = title;
        this.description = description;
        this.publishAt = publishAt;
        this.madeForKids = madeForKids;
        this.status = Status.UPLOADING;
    }

    public void finishUpload(String videoId) {
        if (status != Status.UPLOADING) throw new IllegalStateException("Bozza non in caricamento");
        this.youtubeVideoId = videoId;
        this.status = Status.SCHEDULED;
    }

    public void markUnknown() {
        if (status == Status.UPLOADING) this.status = Status.UNKNOWN;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getBrief() { return brief; }
    public Instant getPublishAt() { return publishAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getYoutubeVideoId() { return youtubeVideoId; }
    public boolean isMadeForKids() { return madeForKids; }
    public Status getStatus() { return status; }
}
