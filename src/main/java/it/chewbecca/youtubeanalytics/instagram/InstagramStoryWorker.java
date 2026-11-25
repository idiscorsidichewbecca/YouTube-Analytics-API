package it.chewbecca.youtubeanalytics.instagram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;

@Component
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class InstagramStoryWorker {
    private final YouTubeQuizCalendar calendar; private final JdbcTemplate db; private final InstagramGraphClient graph; private final Clock clock; private final String publicUrl; private final boolean live;
    public InstagramStoryWorker(JdbcTemplate db,InstagramGraphClient graph,Clock clock,YouTubeQuizCalendar calendar,
        @Value("${app.instagram.public-url}") String publicUrl,@Value("${app.instagram.live-enabled:false}") boolean live){
        var uri=URI.create(publicUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new IllegalArgumentException("Instagram richiede un URL pubblico HTTPS del backend");
        this.calendar=calendar;this.db=db;this.graph=graph;this.clock=clock;this.publicUrl=publicUrl.replaceAll("/+$","");this.live=live;
    }
    record Pending(String id,String assetKey){}
    record PlannedAnswer(OffsetDateTime at){}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    @Scheduled(fixedDelayString="${app.instagram.scheduler-delay-ms:30000}")
    public void publishDue(){
        if(!live)return;
        db.update("UPDATE ig_quiz_story SET status='UNKNOWN',error_code='INTERRUPTED_WORKER_CHECK_META' WHERE account_id=? AND status='PUBLISHING' AND publish_at<?",graph.account(),now().minusHours(2));
        db.update("UPDATE ig_quiz_story SET status='MISSED',error_code='PUBLICATION_WINDOW_EXPIRED' WHERE account_id=? AND status='SCHEDULED' AND publish_at<?",graph.account(),now().minusHours(1));
        db.update("UPDATE ig_quiz_story SET status='BLOCKED',error_code='QUESTION_NOT_PUBLISHED' WHERE account_id=? AND kind='ANSWER' AND status='SCHEDULED' AND publish_at<=? AND EXISTS (SELECT 1 FROM ig_quiz_story q WHERE q.quiz_id=ig_quiz_story.quiz_id AND q.kind='QUESTION' AND q.status IN ('UNKNOWN','FAILED','MISSED','CANCELLED','BLOCKED'))",graph.account(),now());
        var due=db.query("SELECT id,asset_key FROM ig_quiz_story s WHERE account_id=? AND status='SCHEDULED' AND publish_at<=? AND (kind='QUESTION' OR EXISTS (SELECT 1 FROM ig_quiz_story q WHERE q.quiz_id=s.quiz_id AND q.kind='QUESTION' AND q.status='PUBLISHED')) ORDER BY publish_at LIMIT 2",(r,n)->new Pending(r.getString("id"),r.getString("asset_key")),graph.account(),now());
        for(var p:due) publishOne(p);
    }
    private void publishOne(Pending p){
        if(db.update("UPDATE ig_quiz_story SET status='PUBLISHING' WHERE id=? AND account_id=? AND status='SCHEDULED' AND publish_at>=?",p.id,graph.account(),now().minusHours(1))!=1)return;
        boolean publishAttempted=false;
        try {
            if(!checkCalendar(p))return;
            graph.connection();
            String container=graph.createStory(publicUrl+"/instagram-media/"+p.assetKey);
            db.update("UPDATE ig_quiz_story SET container_id=? WHERE id=? AND status='PUBLISHING'",container,p.id);
            boolean ready=false;
            for(int i=0;i<12;i++){
                String state=graph.containerStatus(container);
                if("FINISHED".equals(state)){ready=true;break;}
                if(!"IN_PROGRESS".equals(state))throw new IllegalStateException("META_CONTAINER_NOT_READY");
                Thread.sleep(5000);
            }
            if(!ready)throw new IllegalStateException("META_CONTAINER_TIMEOUT");
            if(!checkCalendar(p))return;
            publishAttempted=true;
            String mediaId=graph.publish(container);
            db.update("UPDATE ig_quiz_story SET status='PUBLISHED',media_id=?,published_at=?,error_code=NULL WHERE id=? AND status='PUBLISHING'",mediaId,now(),p.id);
        }catch(Exception ex){
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            String code=ex instanceof InstagramGraphClient.GraphFailure?ex.getMessage():"WORKER_ERROR_CHECK_CONFIGURATION";
            db.update("UPDATE ig_quiz_story SET status=?,error_code=? WHERE id=? AND status='PUBLISHING'",publishAttempted?"UNKNOWN":"FAILED",code,p.id);
        }
    }
    private boolean checkCalendar(Pending p){
        try {
            var releases=calendar.snapshot();
            // Use the real prospective publication time, not only the approved time.
            var conflict=YouTubeStoryWindows.conflict(now().toInstant(),releases);
            if(conflict.isEmpty()) {
                // Do not start a question if its paired answer is already known to be unsafe.
                var answers=db.query("SELECT a.publish_at FROM ig_quiz_story q JOIN ig_quiz_story a ON a.quiz_id=q.quiz_id AND a.kind='ANSWER' WHERE q.id=? AND q.kind='QUESTION' AND a.status='SCHEDULED'",(r,n)->new PlannedAnswer(r.getObject(1,OffsetDateTime.class)),p.id);
                for(var answer:answers){
                    conflict=YouTubeStoryWindows.conflict(answer.at().toInstant(),releases);
                    if(conflict.isPresent())break;
                }
            }
            if(conflict.isPresent()){
                db.update("UPDATE ig_quiz_story SET status='BLOCKED',error_code=? WHERE id=? AND status='PUBLISHING'","YOUTUBE_EVENING_CONFLICT_"+conflict.get(),p.id);
                return false;
            }
            return true;
        }catch(YouTubeQuizCalendar.CalendarUnavailable ex){
            db.update("UPDATE ig_quiz_story SET status='BLOCKED',error_code='YOUTUBE_CALENDAR_UNAVAILABLE' WHERE id=? AND status='PUBLISHING'",p.id);
            return false;
        }
    }
    @Scheduled(fixedDelayString="${app.instagram.insights-delay-ms:300000}")
    public void collectInsights(){
        if(!live)return;
        var rows=db.query("SELECT id,media_id FROM ig_quiz_story WHERE account_id=? AND status='PUBLISHED' AND reach_count IS NULL AND published_at<=? AND published_at>? ORDER BY published_at LIMIT 10",(r,n)->new Pending(r.getString("id"),r.getString("media_id")),graph.account(),now().minusHours(20),now().minusHours(21));
        for(var p:rows){try{
            Long value=graph.reach(p.assetKey); if(value!=null)db.update("UPDATE ig_quiz_story SET reach_count=?,reach_at=? WHERE id=? AND reach_count IS NULL AND published_at>?",value,now(),p.id,now().minusHours(21));
        }catch(RuntimeException ignored){/* missing data never becomes zero; next poll may recover */}}
    }
}
