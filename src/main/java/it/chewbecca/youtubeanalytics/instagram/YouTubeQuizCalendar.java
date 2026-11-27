package it.chewbecca.youtubeanalytics.instagram;

import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read the owner's entire uploads playlist, including private scheduled uploads. Never infer Short type. */
@Service
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class YouTubeQuizCalendar {
    private static final Logger log=LoggerFactory.getLogger(YouTubeQuizCalendar.class);
    private final YouTubeDataClient videos;
    private final GoogleAccessTokenService tokens;
    public YouTubeQuizCalendar(YouTubeDataClient videos, GoogleAccessTokenService tokens) {
        this.videos=videos; this.tokens=tokens;
    }
    public List<YouTubeStoryWindows.Release> snapshot() {
        String stage="CREDENTIAL";
        try {
            var credential=tokens.requireCredential();
            String channel=credential.getChannelId(), playlist=credential.getUploadsPlaylistId();
            if(channel==null||channel.isBlank()||playlist==null||playlist.isBlank()) throw new CalendarUnavailable("MISSING_CHANNEL_OR_PLAYLIST");
            stage="UPLOADS_API";
            var result=new ArrayList<YouTubeStoryWindows.Release>();
            var uploads=videos.getAllUploadedVideos(playlist);
            stage="UPLOAD_METADATA";
            for(var video:uploads) {
                if(!video.available()) throw new CalendarUnavailable("UNAVAILABLE_UPLOAD");
                if(!channel.equals(video.channelId())) throw new CalendarUnavailable("CHANNEL_MISMATCH");
                Instant at=video.scheduledPublishAt();
                if(at==null) {
                    if("private".equals(video.privacyStatus())) continue; // unscheduled drafts do not reserve an evening
                    if(!"public".equals(video.privacyStatus())&&!"unlisted".equals(video.privacyStatus())) throw new CalendarUnavailable("UNKNOWN_PRIVACY_STATUS");
                    at=video.publishedAt();
                }
                if(at==null) throw new CalendarUnavailable("MISSING_PUBLICATION_DATE");
                result.add(new YouTubeStoryWindows.Release(video.videoId(),at));
            }
            return List.copyOf(result);
        } catch(RuntimeException ex) {
            // Do not expose tokens, API bodies or treat unavailable data as an empty calendar.
            String reason=ex instanceof CalendarUnavailable c?c.reason():ex instanceof GoogleApiException g?"GOOGLE_HTTP_"+g.getStatusCode()+"_"+YouTubeDataClient.safeGoogleReason(g):stage+"_ERROR";
            log.warn("YOUTUBE_CALENDAR_UNAVAILABLE stage={} reason={} exceptionType={}",stage,reason,ex.getClass().getSimpleName());
            throw new CalendarUnavailable(reason);
        }
    }
    public static final class CalendarUnavailable extends IllegalStateException {
        private final String reason;
        public CalendarUnavailable(){this("UNSPECIFIED");}
        public CalendarUnavailable(String reason){super("YOUTUBE_CALENDAR_UNAVAILABLE: "+reason+" - impossibile verificare tutte le uscite YouTube");this.reason=reason;}
        public String reason(){return reason;}
    }
}
