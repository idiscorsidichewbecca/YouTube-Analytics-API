package it.chewbecca.youtubeanalytics.instagram;

import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read the owner's entire uploads playlist, including private scheduled uploads. Never infer Short type. */
@Service
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class YouTubeQuizCalendar {
    private final YouTubeDataClient videos;
    private final GoogleAccessTokenService tokens;
    public YouTubeQuizCalendar(YouTubeDataClient videos, GoogleAccessTokenService tokens) {
        this.videos=videos; this.tokens=tokens;
    }
    public List<YouTubeStoryWindows.Release> snapshot() {
        try {
            var credential=tokens.requireCredential();
            String channel=credential.getChannelId(), playlist=credential.getUploadsPlaylistId();
            if(channel==null||channel.isBlank()||playlist==null||playlist.isBlank()) throw new IllegalStateException();
            var result=new ArrayList<YouTubeStoryWindows.Release>();
            for(var video:videos.getAllUploadedVideos(playlist)) {
                if(!video.available()||!channel.equals(video.channelId())) throw new IllegalStateException();
                Instant at=video.scheduledPublishAt();
                if(at==null) {
                    if("private".equals(video.privacyStatus())) continue; // unscheduled drafts do not reserve an evening
                    if(!"public".equals(video.privacyStatus())&&!"unlisted".equals(video.privacyStatus())) throw new IllegalStateException();
                    at=video.publishedAt();
                }
                if(at==null) throw new IllegalStateException();
                result.add(new YouTubeStoryWindows.Release(video.videoId(),at));
            }
            return List.copyOf(result);
        } catch(RuntimeException ex) {
            // Do not expose tokens, API bodies or treat unavailable data as an empty calendar.
            throw new CalendarUnavailable();
        }
    }
    public static final class CalendarUnavailable extends IllegalStateException {
        public CalendarUnavailable(){super("YOUTUBE_CALENDAR_UNAVAILABLE: impossibile verificare tutte le uscite YouTube");}
    }
}
