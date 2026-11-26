import it.chewbecca.youtubeanalytics.instagram.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.io.*;
public class QuizSmoke {
 static int checks;
 static void check(boolean b){if(!b)throw new AssertionError();checks++;}
 public static void main(String[] args)throws Exception{
  var empty=QuizSlots.choose("QUESTION",List.of(),0);check(empty.hour()==9&&empty.method().startsWith("EXPLORATION"));
  check(QuizSlots.choose("ANSWER",List.of(),0).hour()==16);
  check(QuizSlots.choose("QUESTION",List.of(),1).hour()==8);
  var s=List.of(new QuizSlots.Sample(8,10),new QuizSlots.Sample(8,20),new QuizSlots.Sample(8,30),new QuizSlots.Sample(9,90),new QuizSlots.Sample(9,80),new QuizSlots.Sample(9,100));
  check(QuizSlots.choose("QUESTION",s,0).hour()==9);
  check(QuizSlots.choose("QUESTION",s,0).method().equals("BEST_OBSERVED_MEDIAN"));
  check(QuizSlots.choose("QUESTION",List.of(new QuizSlots.Sample(9,-1)),0).evidence().getFirst().samples()==0);
  var p=Files.createTempFile("story-test",".png");try{
    ImageIO.write(new BufferedImage(540,960,BufferedImage.TYPE_INT_RGB),"png",p.toFile());
    var b=StoryImages.normalize(p);var i=ImageIO.read(new ByteArrayInputStream(b));check(i.getWidth()==1080&&i.getHeight()==1920);
    ImageIO.write(new BufferedImage(960,540,BufferedImage.TYPE_INT_RGB),"png",p.toFile());
    try{StoryImages.normalize(p);throw new AssertionError();}catch(IllegalArgumentException ex){checks++;}
  }finally{Files.deleteIfExists(p);}
  System.out.println(checks+" checks passed");
 }
}
