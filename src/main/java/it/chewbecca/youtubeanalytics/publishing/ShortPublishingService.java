package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ShortPublishingService {
    private final ShortPublicationPlanner planner;
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
                                  Clock clock) {
        this.planner = planner;
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

    public ShortDraft getDraft(String id) {
        return drafts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bozza non trovata: " + id));
    }

    /** Solo l'esplicita conferma dell'ADMIN può far partire l'upload. */
    public ShortDraft schedule(String id, MultipartFile video, String titleOverride,
                               String descriptionOverride, Instant timeOverride) {
        YouTubeShortUploadClient.validateFile(video);
        tokens.requireUploadScope();
        ShortDraft current = getDraft(id);
        String title = StringUtils.hasText(titleOverride) ? titleOverride.trim() : current.getTitle();
        String description = StringUtils.hasText(descriptionOverride)
                ? descriptionOverride.trim() : current.getDescription();
        Instant when = timeOverride == null ? current.getPublishAt() : timeOverride;
        if (title.codePointCount(0, title.length()) > 100 || description.length() > 5000) {
            throw new IllegalArgumentException("Titolo max 100 caratteri e descrizione max 5000");
        }
        Instant now = clock.instant();
        if (when.isBefore(now.plus(Duration.ofHours(1))) || when.isAfter(now.plus(Duration.ofHours(48)))) {
            throw new IllegalArgumentException("Seleziona uno slot tra un'ora e 48 ore da adesso");
        }
        boolean kids = current.isMadeForKids();
        transactions.executeWithoutResult(status -> {
            ShortDraft toUpload = getDraft(id);
            toUpload.startUpload(title, description, when, kids);
            drafts.saveAndFlush(toUpload);
        });
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
}
