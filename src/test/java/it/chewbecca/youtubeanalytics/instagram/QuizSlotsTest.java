package it.chewbecca.youtubeanalytics.instagram;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class QuizSlotsTest {
 @Test void noDataIsExploration(){var c=QuizSlots.choose("QUESTION",List.of(),0);assertEquals(9,c.hour());assertEquals("EXPLORATION_INSUFFICIENT_EVIDENCE",c.method());assertNull(c.evidence().getFirst().medianReach());}
 @Test void comparesMedians(){var samples=List.of(new QuizSlots.Sample(8,5),new QuizSlots.Sample(8,10),new QuizSlots.Sample(8,1000),new QuizSlots.Sample(9,40),new QuizSlots.Sample(9,50),new QuizSlots.Sample(9,60));assertEquals(9,QuizSlots.choose("QUESTION",samples,0).hour());}
 @Test void missingDataDoesNotBecomeZero(){assertEquals(0,QuizSlots.choose("ANSWER",List.of(new QuizSlots.Sample(20,-1)),0).evidence().getFirst().samples());}
 @Test void answersOnlyInEvening(){for(int i=0;i<20;i++){var c=QuizSlots.choose("ANSWER",List.of(),i);assertTrue(c.hour()>=18&&c.hour()<=22);}}
}
