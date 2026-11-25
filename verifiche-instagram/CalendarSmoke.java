import it.chewbecca.youtubeanalytics.instagram.YouTubeStoryWindows;
import it.chewbecca.youtubeanalytics.instagram.YouTubeStoryWindows.Release;
import java.time.*;
import java.util.*;
public class CalendarSmoke {
 static int checks;
 static Instant at(String day,int h,int m){return LocalDate.parse(day).atTime(h,m).atZone(YouTubeStoryWindows.ROME).toInstant();}
 static void check(boolean b){if(!b)throw new AssertionError("check "+(checks+1));checks++;}
 public static void main(String[] args){
  var wed=List.of(new Release("video",at("2026-11-25",17,0)));
  check(YouTubeStoryWindows.conflict(at("2026-11-24",19,0),wed).isPresent());
  check(YouTubeStoryWindows.conflict(at("2026-11-24",9,0),wed).isEmpty());
  check(YouTubeStoryWindows.conflict(at("2026-11-24",17,0),wed).isEmpty());
  check(YouTubeStoryWindows.conflict(at("2026-11-24",17,1),wed).isPresent());
  check(YouTubeStoryWindows.conflict(at("2026-11-25",8,0),wed).isPresent());
  check(YouTubeStoryWindows.conflict(at("2026-11-25",22,0),wed).isPresent());
  check(YouTubeStoryWindows.conflict(at("2026-11-26",0,0),wed).isEmpty());
  var shortMorning=List.of(new Release("short",at("2026-11-25",11,0)));
  check(YouTubeStoryWindows.conflict(at("2026-11-24",19,0),shortMorning).isPresent());
  var start=LocalDate.parse("2026-11-24");var end=start.plusDays(10);var earliest=at("2026-11-23",0,0);
  check(YouTubeStoryWindows.nextDay(start,end,9,20,earliest,wed,Set.of()).orElseThrow().equals(start.plusDays(2)));
  check(YouTubeStoryWindows.nextDay(start,end,9,20,earliest,wed,Set.of(start.plusDays(2))).orElseThrow().equals(start.plusDays(3)));
  check(YouTubeStoryWindows.nextDay(start,start.plusDays(1),9,20,earliest,wed,Set.of()).isEmpty());
  check(YouTubeStoryWindows.nextDay(start,end,9,20,at("2026-11-26",10,0),wed,Set.of()).orElseThrow().equals(start.plusDays(3)));
  check(YouTubeStoryWindows.nextDay(start,end,9,20,earliest,List.of(),Set.of()).orElseThrow().equals(start));
  var spring=List.of(new Release("video",at("2027-03-28",17,0)));
  check(YouTubeStoryWindows.conflict(at("2027-03-27",16,30),spring).isPresent()); // spring: expires 17:30 local
  var autumn=List.of(new Release("video",at("2026-10-25",17,0)));
  check(YouTubeStoryWindows.conflict(at("2026-10-24",17,30),autumn).isEmpty()); // autumn: expires 16:30 local
  check(YouTubeStoryWindows.conflict(at("2026-10-24",18,30),autumn).isPresent());
  check(YouTubeStoryWindows.conflict(at("2026-11-25",22,0),List.of()).isEmpty());
  System.out.println(checks+" calendar checks passed");
 }
}
