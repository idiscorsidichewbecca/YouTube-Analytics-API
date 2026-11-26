package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

@Service
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class InstagramQuizService {
    public static final ZoneId ROME=ZoneId.of("Europe/Rome");
    private final YouTubeQuizCalendar calendar; private final boolean live; private final JdbcTemplate db; private final InstagramGraphClient graph; private final Clock clock; private final ObjectMapper json;
    public InstagramQuizService(JdbcTemplate db,InstagramGraphClient graph,Clock clock,ObjectMapper json,YouTubeQuizCalendar calendar, @org.springframework.beans.factory.annotation.Value("${app.instagram.live-enabled:false}") boolean live) {this.calendar=calendar;this.live=live;this.db=db;this.graph=graph;this.clock=clock;this.json=json;}
    public record QuizInput(String continuity,String question,String answer,String source) {}
    public record Story(String id,String kind,OffsetDateTime publishAt,String status,boolean hasImage,String mediaId,String errorCode) {}
    public record Quiz(String id,int position,LocalDate day,String continuity,String question,String answer,String source,String slotEvidence,List<Story> stories) {}
    public record Batch(String id,String status,String revision,List<Quiz> quizzes,String confirmation) {}
    String account(){return graph.account();}
    OffsetDateTime now(){return OffsetDateTime.now(clock);}
    public List<Map<String,Object>> history(int page) {
        if(page<1 || page>10000) throw new IllegalArgumentException("Pagina non valida");
        return db.queryForList("SELECT q.question,q.answer,q.source,q.continuity,q.quiz_day,b.status FROM ig_quiz q JOIN ig_quiz_batch b ON b.id=q.batch_id WHERE q.account_id=? ORDER BY q.quiz_day DESC,q.id LIMIT 50 OFFSET ?",account(),(page-1)*50);
    }
    public Map<String,Object> context(){return Map.of("youtubeCalendarRule","Proteggi 17:00-24:00 di ogni giorno con video o Short; entrambe le storie visibili 24h; intervallo minimo, sposta in avanti e ricontrolla prima di approvare e pubblicare", "historyPageSize",50,"zone","Europe/Rome","morningHours",List.of(8,9,10,11),"afternoonHours",List.of(15,16,17),"questionSlots",QuizSlots.choose("QUESTION",samples("QUESTION"),0).evidence(),"answerSlots",QuizSlots.choose("ANSWER",samples("ANSWER"),0).evidence(),"weekdayEvidence",QuizSlots.weekdays(samples("QUESTION"),samples("ANSWER")),"style","Verticale 9:16, 1080x1920, italiano, fondo cinematografico, giallo #FED000, margini alti e bassi sicuri, nessuno sticker; domanda aperta senza indizi visivi; risposta con spiegazione e fonte leggibile. Usa riferimenti grafici forniti dall'utente.");}
    @Transactional
    public Batch prepare(LocalDate start,int interval,List<QuizInput> entries){
        if(entries==null||entries.isEmpty()||entries.size()>20||interval<1||interval>30||start==null) throw new IllegalArgumentException("Da 1 a 20 quiz, intervallo 1-30 giorni");
        if(start.isBefore(now().atZoneSameInstant(ROME).toLocalDate())||start.plusDays((long)interval*(entries.size()-1)).isAfter(now().atZoneSameInstant(ROME).toLocalDate().plusDays(180))) throw new IllegalArgumentException("Calendario entro i prossimi 180 giorni");
        String batch=uuid(); db.update("INSERT INTO ig_quiz_batch(id,account_id,status,revision,created_at) VALUES(?,?,'DRAFT',?,?)",batch,account(),uuid(),now());
        var qSamples=samples("QUESTION");var aSamples=samples("ANSWER");
        var releases=calendar.snapshot();
        var reserved=new HashSet<>(db.queryForList("SELECT quiz_day FROM ig_quiz WHERE account_id=?",LocalDate.class,account()));
        LocalDate next=start, last=now().atZoneSameInstant(ROME).toLocalDate().plusDays(180);
        int prior=db.queryForObject("SELECT COUNT(*) FROM ig_quiz WHERE account_id=?",Integer.class,account());
        for(int i=0;i<entries.size();i++){
            var e=entries.get(i);String q=valid(e.question,20,300),a=valid(e.answer,20,750),s=valid(e.source,8,800),c=valid(e.continuity,3,10).toUpperCase(Locale.ROOT);
            if(!Set.of("CANON","LEGENDS").contains(c))throw new IllegalArgumentException("Specificare CANON o LEGENDS");
            var requestedDay=next;
            var plan=QuizSlots.plan(next,last,now().plusMinutes(5).toInstant(),releases,reserved,qSamples,aSamples,prior+i)
                    .orElseThrow(()->new IllegalStateException("Nessuna coppia di orari compatibile con YouTube entro 180 giorni; batch non creato"));
            var day=plan.day(); var morning=plan.question(); var afternoon=plan.answer();
            next=day.plusDays(interval); reserved.add(day);
            var atQ=day.atTime(morning.hour(),0).atZone(ROME).toOffsetDateTime();var atA=day.atTime(afternoon.hour(),0).atZone(ROME).toOffsetDateTime();
            if(!atQ.isAfter(now().plusMinutes(5)))throw new IllegalArgumentException("La prima domanda deve essere futura; scegliere un altro giorno");
            String id=uuid(),hash=hash(q);
            if(db.queryForObject("SELECT COUNT(*) FROM ig_quiz WHERE account_id=? AND (question_hash=? OR quiz_day=?)",Integer.class,account(),hash,day)>0)throw new IllegalArgumentException("Domanda già utilizzata o giorno già riservato: "+day);
            db.update("INSERT INTO ig_quiz(id,batch_id,account_id,position_no,quiz_day,continuity,question,answer,source,question_hash,slot_evidence) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,batch,account(),i+1,day,c,q,a,s,hash,encode(Map.of("morning",morning,"afternoon",afternoon,"weekdayMethod",plan.dayMethod(),"weekdayEvidence",plan.dayEvidence(),"requestedDay",requestedDay.toString(),"youtubeProtectedEvening", "17:00-24:00 Europe/Rome; durata storia 24h", "shiftedForCalendarOrAvailability",!day.equals(requestedDay))));
            insertStory(id,"QUESTION",atQ);insertStory(id,"ANSWER",atA);
        }
        return status(batch);
    }
    /**
     * Manual single quiz: NOW means enqueue at approval, not at draft creation.
     * A missing answer time creates only the question story; the solution remains
     * in the quiz history for verification. Calendar protection remains mandatory.
     */
    @Transactional
    public Batch prepareManual(QuizInput e,String questionAt,String answerAt){
        if(e==null||questionAt==null)throw new IllegalArgumentException("Quiz e orario domanda obbligatori");
        var current=now().atZoneSameInstant(ROME).toOffsetDateTime();
        boolean immediate="NOW".equalsIgnoreCase(questionAt.trim());
        var atQ=immediate?current:manualTime(questionAt);
        var atA=answerAt==null||answerAt.isBlank()?null:manualTime(answerAt);
        if(!immediate&&!atQ.isAfter(current.plusMinutes(2)))throw new IllegalArgumentException("Domanda futura di almeno 2 minuti oppure NOW");
        if(atA!=null&&(!atA.isAfter(atQ)||!atA.isAfter(current.plusMinutes(2))))throw new IllegalArgumentException("La risposta deve seguire la domanda ed essere futura");
        var limit=current.atZoneSameInstant(ROME).plusDays(180).toInstant();
        if(atQ.toInstant().isAfter(limit)||(atA!=null&&atA.toInstant().isAfter(limit)))throw new IllegalArgumentException("Calendario entro i prossimi 180 giorni");
        String q=valid(e.question,20,300),a=valid(e.answer,20,750),s=valid(e.source,8,800),c=valid(e.continuity,3,10).toUpperCase(Locale.ROOT);
        if(!Set.of("CANON","LEGENDS").contains(c))throw new IllegalArgumentException("Specificare CANON o LEGENDS");
        var day=atQ.atZoneSameInstant(ROME).toLocalDate();
        String hash=hash(q);
        if(db.queryForObject("SELECT COUNT(*) FROM ig_quiz WHERE account_id=? AND (question_hash=? OR quiz_day=?)",Integer.class,account(),hash,day)>0)throw new IllegalArgumentException("Domanda già utilizzata o giorno già riservato: "+day);
        var releases=calendar.snapshot();
        for(var at:atA==null?List.of(atQ):List.of(atQ,atA)){
            var conflict=YouTubeStoryWindows.conflict(at.toInstant(),releases);
            if(conflict.isPresent())throw new IllegalArgumentException("Conflitto calendario YouTube: sera protetta "+conflict.get());
        }
        String batch=uuid(),id=uuid();
        db.update("INSERT INTO ig_quiz_batch(id,account_id,status,revision,created_at) VALUES(?,?,'DRAFT',?,?)",batch,account(),uuid(),now());
        db.update("INSERT INTO ig_quiz(id,batch_id,account_id,position_no,quiz_day,continuity,question,answer,source,question_hash,slot_evidence) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,batch,account(),1,day,c,q,a,s,hash,encode(Map.of("mode",immediate?"MANUAL_NOW":"MANUAL_FIXED")));
        insertStory(id,"QUESTION",atQ);
        if(atA!=null)insertStory(id,"ANSWER",atA);
        return status(batch);
    }
    static OffsetDateTime manualTime(String value){
        // An explicit offset is required. Reject a mismatched Italian offset,
        // including non-existent wall times at the spring DST transition.
        var at=OffsetDateTime.parse(value.trim());
        if(!ROME.getRules().getValidOffsets(at.toLocalDateTime()).contains(at.getOffset()))
            throw new IllegalArgumentException("Usare data e ora italiana ISO-8601 con offset corretto");
        return at;
    }
    private void insertStory(String quiz,String kind,OffsetDateTime at){db.update("INSERT INTO ig_quiz_story(id,quiz_id,account_id,kind,publish_at,status,asset_key) VALUES(?,?,?,?,?,'DRAFT',?)",uuid(),quiz,account(),kind,at,uuid().replace("-","")+uuid().replace("-",""));}
    @Transactional
    public Batch stage(String batch,int position,byte[] question,byte[] answer){
        requireDraft(batch); if(question==null||question.length==0||(answer!=null&&(answer.length==0||Arrays.equals(question,answer))))throw new IllegalArgumentException("Grafiche mancanti o identiche");
        var quiz=db.queryForList("SELECT id FROM ig_quiz WHERE batch_id=? AND account_id=? AND position_no=?",String.class,batch,account(),position);
        if(quiz.size()!=1)throw new IllegalArgumentException("Posizione quiz inesistente");
        boolean paired=stories(quiz.getFirst()).stream().anyMatch(s->"ANSWER".equals(s.kind));
        if(paired!=(answer!=null))throw new IllegalArgumentException(paired?"Servono entrambe le grafiche":"Questo quiz prevede solo la domanda");
        db.update("UPDATE ig_quiz_story SET image_data=? WHERE quiz_id=? AND kind='QUESTION' AND status='DRAFT'",Base64.getEncoder().encodeToString(question),quiz.getFirst());
        if(paired)db.update("UPDATE ig_quiz_story SET image_data=? WHERE quiz_id=? AND kind='ANSWER' AND status='DRAFT'",Base64.getEncoder().encodeToString(answer),quiz.getFirst());
        db.update("UPDATE ig_quiz_batch SET revision=? WHERE id=?",uuid(),batch);return status(batch);
    }
    @Transactional
    public Batch schedule(String id,String confirmation){
        if(!live)throw new IllegalStateException("Pubblicazione Instagram disabilitata (app.instagram.live-enabled=false)");
        var b=lockBatch(id); if(!"DRAFT".equals(b.get("status")))throw new IllegalStateException("Batch non in bozza");
        if(!confirmation(id,(String)b.get("revision")).equals(confirmation))throw new IllegalArgumentException("Conferma esplicita della revisione corrente richiesta");
        var current=status(id);
        var approvedAt=now().atZoneSameInstant(ROME).toOffsetDateTime();
        var immediateIds=new HashSet<String>();
        for(var q:current.quizzes){
            if(q.slotEvidence.equals(encode(Map.of("mode","MANUAL_NOW")))){
                if(!q.day.equals(approvedAt.toLocalDate()))throw new IllegalStateException("Bozza NOW di un altro giorno; creare una nuova bozza");
                for(var story:q.stories)if("QUESTION".equals(story.kind)){
                    immediateIds.add(story.id);
                    db.update("UPDATE ig_quiz_story SET publish_at=? WHERE id=? AND status='DRAFT'",approvedAt,story.id);
                }
            }
        }
        current=status(id);
        if(current.quizzes.stream().flatMap(q->q.stories.stream()).anyMatch(s->!s.hasImage||(!immediateIds.contains(s.id)&&!s.publishAt.isAfter(approvedAt.plusMinutes(2)))))throw new IllegalStateException("Mancano immagini oppure alcuni orari sono scaduti");
        var releases=calendar.snapshot();
        for(var q:current.quizzes) for(var story:q.stories) {
            var conflict=YouTubeStoryWindows.conflict(story.publishAt.toInstant(),releases);
            if(conflict.isPresent()) throw new IllegalStateException("Conflitto calendario YouTube: quiz "+q.position+" "+story.kind+" (sera protetta "+conflict.get()+"). Batch non programmato; servono nuove date e approvazione.");
        }
        graph.requirePublishingConnection();
        db.update("UPDATE ig_quiz_story SET status='SCHEDULED' WHERE quiz_id IN (SELECT id FROM ig_quiz WHERE batch_id=?) AND status='DRAFT'",id);
        db.update("UPDATE ig_quiz_batch SET status='SCHEDULED' WHERE id=?",id); return status(id);
    }
    @Transactional
    public Batch cancel(String id,String confirmation){
        var b=lockBatch(id);if(!("ANNULLA QUIZ "+id).equals(confirmation))throw new IllegalArgumentException("Conferma ANNULLA QUIZ <id> richiesta");
        if("CANCELLED".equals(b.get("status")))return status(id);
        db.update("UPDATE ig_quiz_story SET status='CANCELLED',image_data=NULL WHERE quiz_id IN (SELECT id FROM ig_quiz WHERE batch_id=?) AND status IN ('DRAFT','SCHEDULED')",id);
        db.update("UPDATE ig_quiz_batch SET status='CANCELLED' WHERE id=?",id);return status(id);
    }
    private Map<String,Object> lockBatch(String id){var rows=db.queryForList("SELECT status,revision FROM ig_quiz_batch WHERE id=? AND account_id=? FOR UPDATE",id,account());if(rows.size()!=1)throw new IllegalArgumentException("Batch inesistente");return rows.getFirst();}
    private void requireDraft(String id){if(!"DRAFT".equals(lockBatch(id).get("status")))throw new IllegalStateException("Batch non modificabile");}
    public List<Map<String,Object>> listBatches(int page){
        if(page<1||page>10000)throw new IllegalArgumentException("Pagina non valida");
        return db.queryForList("SELECT id,status,revision,created_at FROM ig_quiz_batch WHERE account_id=? ORDER BY created_at DESC LIMIT 25 OFFSET ?",account(),(page-1)*25);
    }
    public List<byte[]> graphics(String batch,int position){
        var rows=db.queryForList("SELECT s.image_data FROM ig_quiz_story s JOIN ig_quiz q ON q.id=s.quiz_id WHERE q.batch_id=? AND q.account_id=? AND q.position_no=? AND s.image_data IS NOT NULL ORDER BY s.publish_at",String.class,batch,account(),position);
        var expected=db.queryForObject("SELECT COUNT(*) FROM ig_quiz_story s JOIN ig_quiz q ON q.id=s.quiz_id WHERE q.batch_id=? AND q.account_id=? AND q.position_no=?",Integer.class,batch,account(),position);
        if(expected==null||expected==0||rows.size()!=expected)throw new IllegalArgumentException("Grafiche non disponibili");
        return rows.stream().map(x->Base64.getDecoder().decode(x)).toList();
    }
    public Batch status(String id){
        var batch=db.queryForList("SELECT status,revision FROM ig_quiz_batch WHERE id=? AND account_id=?",id,account());if(batch.size()!=1)throw new IllegalArgumentException("Batch inesistente");
        var quizzes=db.query("SELECT * FROM ig_quiz WHERE batch_id=? AND account_id=? ORDER BY position_no",(r,n)->new Quiz(r.getString("id"),r.getInt("position_no"),r.getObject("quiz_day",LocalDate.class),r.getString("continuity"),r.getString("question"),r.getString("answer"),r.getString("source"),r.getString("slot_evidence"),stories(r.getString("id"))),id,account());
        String state=(String)batch.getFirst().get("status"),rev=(String)batch.getFirst().get("revision");return new Batch(id,state,rev,quizzes,"DRAFT".equals(state)&&quizzes.stream().flatMap(q->q.stories.stream()).allMatch(Story::hasImage)?confirmation(id,rev):null);
    }
    private List<Story> stories(String id){return db.query("SELECT id,kind,publish_at,status,CASE WHEN image_data IS NULL THEN FALSE ELSE TRUE END AS has_image,media_id,error_code FROM ig_quiz_story WHERE quiz_id=? ORDER BY publish_at",(r,n)->new Story(r.getString("id"),r.getString("kind"),r.getObject("publish_at",OffsetDateTime.class),r.getString("status"),r.getBoolean("has_image"),r.getString("media_id"),r.getString("error_code")),id);}
    private List<QuizSlots.Sample> samples(String kind){return db.query("SELECT published_at,reach_count FROM ig_quiz_story WHERE account_id=? AND kind=? AND status='PUBLISHED' AND reach_count IS NOT NULL AND published_at>=?",(r,n)->new QuizSlots.Sample(r.getObject("published_at",OffsetDateTime.class).atZoneSameInstant(ROME).getHour(),r.getLong("reach_count"),r.getObject("published_at",OffsetDateTime.class).atZoneSameInstant(ROME).getDayOfWeek()),account(),kind,now().minusDays(120));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Errore serializzazione calendario");}}
    private static String confirmation(String id,String rev){return "CONFERMO QUIZ "+id+" "+rev;}
    private static String uuid(){return UUID.randomUUID().toString();}
    private static String valid(String v,int min,int max){if(v==null||v.trim().length()<min||v.trim().length()>max)throw new IllegalArgumentException("Testo quiz fuori dai limiti");return v.trim();}
    static String hash(String q){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Normalizer.normalize(q,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("\\p{M}|[^\\p{L}\\p{N}]","").getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
