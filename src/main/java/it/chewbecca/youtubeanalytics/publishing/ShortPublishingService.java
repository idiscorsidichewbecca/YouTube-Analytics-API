package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ShortPublishingService {
    private final ShortPublicationPlanner planner;
    private final WeeklyShortPlanner weeklyPlanner;
    private final BatchShortPlanner batchPlanner;
    private final ShortMetadataGenerator metadataGenerator;
    private final ShortDraftRepository drafts;
    private final YouTubeShortUploadClient upload;
    private final GoogleAccessTokenService tokens;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ShortPublishingService(ShortPublicationPlanner planner,
                                  ShortMetadataGenerator metadataGenerator,
                                  ShortDraftRepository drafts, YouTubeShortUploadClient upload,
                                  GoogleAccessTokenService tokens, TransactionTemplate transactions,
                                  Clock clock, WeeklyShortPlanner weeklyPlanner, BatchShortPlanner batchPlanner) {
        this.planner = planner;
        this.weeklyPlanner = weeklyPlanner;
        this.batchPlanner = batchPlanner;
        this.metadataGenerator = metadataGenerator;
        this.drafts = drafts;
        this.upload = upload;
        this.tokens = tokens;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ShortDraft prepare(String brief, boolean madeForKids) {
        if (!StringUtils.hasText(brief) || brief.trim().length() < 30 || brief.length() > 10000) {
            throw new IllegalArgumentException("Fornisci un riassunto/trascrizione del video di 30-10000 caratteri");
        }
        ShortPublicationPlanner.Recommendation recommendation = planner.recommend();
        ShortMetadataGenerator.Metadata text = metadataGenerator.generate(brief);
        return drafts.save(ShortDraft.create(brief, text.title(), text.description(),
                recommendation.recommended().dateTime().toInstant(), madeForKids, clock.instant()));
    }

    /** Variante per ChatGPT: la chat prepara i testi, il backend valida e persiste, nessuna OpenAI API aggiuntiva. */
    public ShortDraft prepareFromChat(String brief, String title, String description, boolean madeForKids) {
        if (!StringUtils.hasText(brief) || brief.trim().length() < 30 || brief.length() > 10000) {
            throw new IllegalArgumentException("Brief/trascrizione: 30-10000 caratteri");
        }
        if (!StringUtils.hasText(title) || title.codePointCount(0, title.length()) > 100 ||
                !StringUtils.hasText(description) || description.length() > 5000) {
            throw new IllegalArgumentException("Titolo obbligatorio max 100 caratteri; descrizione obbligatoria max 5000");
        }
        ShortPublicationPlanner.Recommendation recommendation = planner.recommend();
        return prepareFromChatAt(brief, title, description, madeForKids,
                recommendation.recommended().dateTime().toInstant());
    }

    /** Usa la data scelta dall'utente, senza sovrascriverla con un orario suggerito. */
    public ShortDraft prepareFromChatAt(String brief, String title, String description,
                                        boolean madeForKids, Instant publishAt) {
        return prepareFromChatAt(brief, title, description, madeForKids, publishAt, false);
    }

    public ShortDraft prepareFromChatAt(String brief, String title, String description,
                                       boolean madeForKids, Instant publishAt, boolean weeklyPlan) {
        validateMetadata(brief, title, description);
        Instant now = clock.instant();
        validatePublishAt(publishAt, now);
        ShortDraft draft = ShortDraft.create(brief.trim(), title.trim(), description.trim(),
                publishAt, madeForKids, now);
        if (weeklyPlan) {
            WeeklyShortPlanner.validateCurrentWeek(publishAt, now);
            draft.useWeeklyPlan();
        }
        return drafts.save(draft);
    }

    public BatchPreview prepareBatch(List<BatchEntry> entries, boolean startNextWeek) {
        if (entries.isEmpty() || entries.size() > BatchShortPlanner.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Un batch accetta da 1 a 10 Shorts");
        }
        Set<String> names = new HashSet<>();
        for (BatchEntry entry : entries) {
            validateMetadata(entry.brief(), entry.title(), entry.description());
            String filename = entry.filename();
            if (filename == null || filename.length() > 255 || !filename.toLowerCase(Locale.ROOT).endsWith(".mp4")
                    || !names.add(filename.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Ogni Short deve avere un nome MP4 distinto, lungo al massimo 255 caratteri");
            }
        }

        var plan = batchPlanner.plan(entries.size(), startNextWeek);
        String batchId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        List<ShortDraft> batch = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            BatchEntry entry = entries.get(index);
            Instant at = plan.slots().get(index).dateTime().toInstant();
            validatePublishAt(at, now);
            ShortDraft draft = ShortDraft.create(entry.brief().trim(), entry.title().trim(),
                    entry.description().trim(), at, entry.madeForKids(), now);
            draft.addToBatch(batchId, index + 1, entry.filename());
            batch.add(draft);
        }
        List<ShortDraft> saved = drafts.saveAll(batch);
        return new BatchPreview(batchId, saved, plan.slots(), plan.videosAnalyzed(), plan.historyTruncated());
    }

    public ShortDraft getBatchDraft(String batchId, int position) {
        return drafts.findByBatchIdAndBatchPosition(batchId, position)
                .orElseThrow(() -> new IllegalArgumentException("Posizione non trovata nel batch"));
    }

    public ShortDraft scheduleBatch(ShortDraft draft, MultipartFile video, String fileId) {
        if (draft.getBatchId() == null || fileId == null || !fileId.matches("file_[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("Allegato non valido per il batch");
        }
        return schedule(draft, video, null, null, null, fileId);
    }

    public record BatchEntry(String filename, String brief, String title, String description, boolean madeForKids) {}
    public record BatchPreview(String batchId, List<ShortDraft> drafts, List<WeeklyShortPlanner.SlotEvidence> slots,
                               int videosAnalyzed, boolean historyTruncated) {}

    private static void validateMetadata(String brief, String title, String description) {
        if (!StringUtils.hasText(brief) || brief.trim().length() < 30 || brief.length() > 10000) {
            throw new IllegalArgumentException("Brief o trascrizione: da 30 a 10000 caratteri");
        }
        if (!StringUtils.hasText(title) || title.codePointCount(0, title.length()) > 100 ||
                !StringUtils.hasText(description) || description.length() > 5000) {
            throw new IllegalArgumentException("Titolo obbligatorio max 100 caratteri; descrizione obbligatoria max 5000");
        }
    }

    static void validatePublishAt(Instant when, Instant now) {
        if (when == null || when.isBefore(now.plus(Duration.ofHours(1))) ||
                when.isAfter(now.plus(Duration.ofDays(365)))) {
            throw new IllegalArgumentException("La pubblicazione deve essere tra un'ora e 365 giorni da adesso");
        }
    }

    public ShortDraft getDraft(String id) {
        return drafts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bozza non trovata: " + id));
    }

    /** Solo l'esplicita conferma dell'ADMIN può far partire l'upload. */
    public ShortDraft schedule(String id, MultipartFile video, String titleOverride,
                               String descriptionOverride, Instant timeOverride) {
        ShortDraft current = getDraft(id);
        if (current.isWeeklyPlan()) {
            throw new IllegalArgumentException("Conferma le bozze settimanali tramite Chewbecca Studio");
        }
        return schedule(current, video, titleOverride, descriptionOverride, timeOverride, null);
    }

    public ShortDraft scheduleWeekly(String id, MultipartFile video) {
        ShortDraft current = getDraft(id);
        if (current.getBatchId() != null) {
            throw new IllegalArgumentException("Conferma questo Short tramite il suo batch");
        }
        if (!current.isWeeklyPlan()) {
            throw new IllegalArgumentException("La bozza non è una pianificazione settimanale");
        }
        return schedule(current, video, null, null, null, null);
    }

    private ShortDraft schedule(ShortDraft current, MultipartFile video, String titleOverride,
                                String descriptionOverride, Instant timeOverride, String fileId) {
        YouTubeShortUploadClient.validateFile(video);
        tokens.requireUploadScope();
        String id = current.getId();
        String title = StringUtils.hasText(titleOverride) ? titleOverride.trim() : current.getTitle();
        String description = StringUtils.hasText(descriptionOverride)
                ? descriptionOverride.trim() : current.getDescription();
        Instant when = timeOverride == null ? current.getPublishAt() : timeOverride;
        if (title.codePointCount(0, title.length()) > 100 || description.length() > 5000) {
            throw new IllegalArgumentException("Titolo max 100 caratteri e descrizione max 5000");
        }
        boolean kids = current.isMadeForKids();
        reserveUpload(current, title, description, when, kids, fileId);
        try {
            String youtubeId = upload.schedule(video, title, description, when, kids);
            return transactions.execute(status -> {
                ShortDraft published = getDraft(id);
                published.finishUpload(youtubeId);
                return drafts.save(published);
            });
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> {
                ShortDraft uncertain = getDraft(id);
                uncertain.markUnknown();
                drafts.save(uncertain);
            });
            // Mai ritentare automaticamente: Google potrebbe aver ricevuto il file.
            throw ex;
        }
    }

    // Render runs one instance. Hold the lock until the reservation is committed
    // so another confirmation sees it, including while the upload is in progress.
    private synchronized void reserveUpload(ShortDraft current, String title, String description,
                                            Instant when, boolean kids, String fileId) {
        String id = current.getId();
        Instant now = clock.instant();
        validatePublishAt(when, now);
        if (current.getBatchId() != null) {
            if (drafts.existsByBatchIdAndBatchFileId(current.getBatchId(), fileId)) {
                throw new IllegalArgumentException("L'allegato è già stato utilizzato in questo batch");
            }
            batchPlanner.assertAvailable(when, id);
            validatePublishAt(when, clock.instant());
        } else if (current.isWeeklyPlan()) {
            WeeklyShortPlanner.validateCurrentWeek(when, now);
            weeklyPlanner.assertAvailable(when, id);
            validatePublishAt(when, clock.instant());
        }
        transactions.executeWithoutResult(status -> {
            ShortDraft toUpload = getDraft(id);
            toUpload.startUpload(title, description, when, kids);
            if (fileId != null) toUpload.bindBatchFile(fileId);
            drafts.saveAndFlush(toUpload);
        });
    }
}
