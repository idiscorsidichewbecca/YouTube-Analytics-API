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
    @Column(name = "weekly_plan", nullable = false)
    private boolean weeklyPlan;
    @Column(name = "batch_id", length = 36)
    private String batchId;
    @Column(name = "batch_position")
    private Integer batchPosition;
    @Column(name = "batch_filename", length = 255)
    private String batchFilename;
    @Column(name = "batch_file_id", length = 133)
    private String batchFileId;
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

    public void useWeeklyPlan() {
        weeklyPlan = true;
    }

    public boolean isWeeklyPlan() { return weeklyPlan; }

    void addToBatch(String batchId, int position, String filename) {
        useWeeklyPlan();
        this.batchId = batchId;
        this.batchPosition = position;
        this.batchFilename = filename;
    }

    void bindBatchFile(String fileId) { this.batchFileId = fileId; }

    public String getBatchId() { return batchId; }
    public Integer getBatchPosition() { return batchPosition; }
    public String getBatchFilename() { return batchFilename; }

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
