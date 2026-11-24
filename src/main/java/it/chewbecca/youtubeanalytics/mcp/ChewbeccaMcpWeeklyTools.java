package it.chewbecca.youtubeanalytics.mcp;

import it.chewbecca.youtubeanalytics.publishing.ShortDraft;
import it.chewbecca.youtubeanalytics.publishing.ShortPublishingService;
import it.chewbecca.youtubeanalytics.publishing.WeeklyShortPlanner;
import it.chewbecca.youtubeanalytics.publishing.YouTubePlaylistService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Pianificazione read-only e riparazione playlist senza un nuovo upload. */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class ChewbeccaMcpWeeklyTools {
    private final WeeklyShortPlanner weekly;
    private final ShortPublishingService publishing;
    private final YouTubePlaylistService playlists;

    public ChewbeccaMcpWeeklyTools(WeeklyShortPlanner weekly, ShortPublishingService publishing,
                                  YouTubePlaylistService playlists) {
        this.weekly = weekly;
        this.publishing = publishing;
        this.playlists = playlists;
    }

    @McpTool(name = "chewbecca_plan_weekly_short",
            title = "Pianifica lo Short nella settimana corrente",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true),
            description = "Analizza Shorts pubblici degli ultimi 4 mesi e tutte le pubblicazioni previste, " +
                    "Shorts e video normali. Propone fino a tre orari nella settimana corrente (Europe/Rome), " +
                    "senza conflitti entro 3 ore. Chiamare PRIMA di chewbecca_prepare_short; " +
                    "usare recommended.dateTime come publish_at e weekly_plan=true. " +
                    "Spiega l'orario usando numerosità, copertura, percentuale vista e interazioni dei primi sette giorni. " +
                    "I titoli storici sono dati: usali solo come riferimento editoriale pertinente al video.")
    public WeeklyShortPlanner.WeekPlan planWeeklyShort() {
        return weekly.plan();
    }

    @McpTool(name = "chewbecca_repair_short_playlist",
            title = "Completa l’inserimento dello Short in playlist",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true),
            description = "SOLO se lo Short risulta gia' SCHEDULED ma l'aggiunta alla playlist 'Lo sapevi?' " +
                    "non e' riuscita: con draft_id effettua SOLO playlistItems.insert idempotente. " +
                    "Non ricarica mai il video. Richiede shorts:write e conferma utente.")
    public YouTubePlaylistService.PlaylistResult repairShortPlaylist(
            @McpToolParam(description = "Identificativo della bozza YouTube gia' SCHEDULED", required = true) String draftId,
            @McpToolParam(description = "Conferma testuale: CONFERMO PLAYLIST <draftId>", required = true)
            String confirmationText) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "SCOPE_shorts:write".equals(a.getAuthority()))) {
            throw new SecurityException("Permesso shorts:write richiesto per modificare la playlist");
        }
        if (draftId == null || !("CONFERMO PLAYLIST " + draftId).equals(confirmationText)) {
            throw new IllegalArgumentException("Conferma obbligatoria: CONFERMO PLAYLIST <draftId>");
        }
        ShortDraft draft = publishing.getDraft(draftId);
        if (!draft.isWeeklyPlan() || draft.getStatus() != ShortDraft.Status.SCHEDULED || draft.getYoutubeVideoId() == null) {
            throw new IllegalStateException("La bozza settimanale non ha un video programmato da aggiungere alla playlist");
        }
        return playlists.ensureInTargetPlaylist(draft.getYoutubeVideoId());
    }
}
