package it.chewbecca.youtubeanalytics.instagram;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class QuizSlotsTest {
 @Test void noDataIsExploration(){var c=QuizSlots.choose("QUESTION",List.of(),0);assertEquals(9,c.hour());assertEquals("EXPLORATION_INSUFFICIENT_EVIDENCE",c.method());assertNull(c.evidence().getFirst().medianReach());}
 @Test void comparesMedians(){var samples=List.of(new QuizSlots.Sample(8,5),new QuizSlots.Sample(8,10),new QuizSlots.Sample(8,1000),new QuizSlots.Sample(9,40),new QuizSlots.Sample(9,50),new QuizSlots.Sample(9,60));assertEquals(9,QuizSlots.choose("QUESTION",samples,0).hour());}
 @Test void missingDataDoesNotBecomeZero(){assertEquals(0,QuizSlots.choose("ANSWER",List.of(new QuizSlots.Sample(16,-1)),0).evidence().getFirst().samples());}
 @Test void answersOnlyInAfternoon(){for(int i=0;i<20;i++){var c=QuizSlots.choose("ANSWER",List.of(),i);assertTrue(c.hour()>=15&&c.hour()<=17);}}
 @Test void chooses16WithoutData(){assertEquals(16,QuizSlots.choose("ANSWER",List.of(),0).hour());}
 @Test void weekdayEvidenceChoosesBetterCompatibleDay(){
  var mon=java.time.DayOfWeek.MONDAY;var tue=java.time.DayOfWeek.TUESDAY;
  var samples=List.of(new QuizSlots.Sample(9,10,mon),new QuizSlots.Sample(9,20,mon),new QuizSlots.Sample(9,30,mon),new QuizSlots.Sample(9,90,tue),new QuizSlots.Sample(9,100,tue),new QuizSlots.Sample(9,110,tue));
  var answers=samples.stream().map(x->new QuizSlots.Sample(16,x.reach(),x.weekday())).toList();
  var start=java.time.LocalDate.of(2026,11,30);
  var plan=QuizSlots.plan(start,start.plusDays(6),java.time.Instant.parse("2026-11-29T00:00:00Z"),List.of(),java.util.Set.of(),samples,answers,0).orElseThrow();
  assertEquals(start.plusDays(1),plan.day());assertEquals("BEST_OBSERVED_WEEKDAY_MEDIAN",plan.dayMethod());
  var blocked=QuizSlots.plan(start,start.plusDays(6),java.time.Instant.parse("2026-11-29T00:00:00Z"),List.of(),java.util.Set.of(start.plusDays(1)),samples,answers,0).orElseThrow();
  assertEquals(start,blocked.day());assertEquals("EARLIEST_COMPATIBLE_INSUFFICIENT_EVIDENCE",blocked.dayMethod());
 }
}
