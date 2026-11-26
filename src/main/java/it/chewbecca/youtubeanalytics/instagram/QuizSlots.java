package it.chewbecca.youtubeanalytics.instagram;

import java.util.*;
import java.time.*;

/** Comparable observations collected at age 20-21h, local Italian publication hour. */
public final class QuizSlots {
    private QuizSlots() {}
    public record Sample(int hour, long reach, DayOfWeek weekday) {
        public Sample(int hour,long reach){this(hour,reach,null);}
    }
    public record Evidence(int hour, int samples, Double medianReach, String confidence) {}
    public record Choice(int hour, String method, List<Evidence> evidence) {}
    public static Choice choose(String kind, List<Sample> samples, int ordinal) {
        List<Integer> hours = switch (kind) {
            case "QUESTION" -> List.of(9, 8, 10, 11);
            case "ANSWER" -> List.of(16, 15, 17);
            default -> throw new IllegalArgumentException("Tipo storia non valido");
        };
        var evidence = hours.stream().map(h -> {
            var values = samples.stream().filter(s -> s.hour == h && s.reach >= 0).map(Sample::reach).sorted().toList();
            int n = values.size();
            Double median = n == 0 ? null : n % 2 == 1 ? (double) values.get(n/2) : values.get(n/2-1)/2.0 + values.get(n/2)/2.0;
            return new Evidence(h, n, median, n >= 8 ? "HIGH_SAMPLE" : n >= 3 ? "MEDIUM_SAMPLE" : "LOW_SAMPLE");
        }).toList();
        var ranked = evidence.stream().filter(e -> e.samples >= 3)
                .sorted(Comparator.comparing(Evidence::medianReach).reversed().thenComparing(Comparator.comparingInt(Evidence::samples).reversed())).toList();
        if (ranked.size() >= 2) {
            return new Choice(ranked.getFirst().hour, "BEST_OBSERVED_MEDIAN", evidence);
        }
        if (kind.equals("ANSWER") && evidence.stream().allMatch(e -> e.samples == 0))
            return new Choice(16, "EXPLORATION_INSUFFICIENT_EVIDENCE", evidence);
        var least = evidence.stream().mapToInt(Evidence::samples).min().orElse(0);
        var candidates = evidence.stream().filter(e -> e.samples == least).toList();
        return new Choice(candidates.get(Math.floorMod(ordinal, candidates.size())).hour,
                "EXPLORATION_INSUFFICIENT_EVIDENCE", evidence);
    }
    public record DayEvidence(DayOfWeek weekday, int questionSamples, int answerSamples,
                              Double questionMedian, Double answerMedian) {}
    public record Plan(LocalDate day, Choice question, Choice answer, String dayMethod, List<DayEvidence> dayEvidence) {}
    private static List<Sample> validDay(List<Sample> samples, DayOfWeek day, String kind) {
        return samples.stream().filter(s->s.weekday==day && s.reach>=0 &&
                (kind.equals("QUESTION")?s.hour>=8&&s.hour<=11:s.hour>=15&&s.hour<=17)).toList();
    }
    private static Double median(List<Sample> samples) {
        var v=samples.stream().map(Sample::reach).sorted().toList();
        int n=v.size();
        return n==0?null:n%2==1?(double)v.get(n/2):v.get(n/2-1)/2.0+v.get(n/2)/2.0;
    }
    public static List<DayEvidence> weekdays(List<Sample> q,List<Sample> a) {
        return Arrays.stream(DayOfWeek.values()).map(day->{
            var qs=validDay(q,day,"QUESTION");var as=validDay(a,day,"ANSWER");
            return new DayEvidence(day,qs.size(),as.size(),median(qs),median(as));
        }).toList();
    }
    public static Optional<Plan> plan(LocalDate start,LocalDate last,Instant earliest,
            Collection<YouTubeStoryWindows.Release> releases,Set<LocalDate> reserved,
            List<Sample> questions,List<Sample> answers,int ordinal){
        var evidence=weekdays(questions,answers);
        // Only compare days supported by at least three observations of EACH kind.
        var supported=evidence.stream().filter(e->e.questionSamples>=3&&e.answerSamples>=3).toList();
        var candidates=new ArrayList<Plan>();
        LocalDate first=null;
        for(LocalDate d=start;!d.isAfter(last);d=d.plusDays(1)){
            if(first!=null&&d.isAfter(first.plusDays(6)))break;
            if(reserved.contains(d))continue;
            var qs=validDay(questions,d.getDayOfWeek(),"QUESTION");
            var as=validDay(answers,d.getDayOfWeek(),"ANSWER");
            var q=choose("QUESTION",qs,ordinal);var a=choose("ANSWER",as,ordinal);
            if(!q.method.equals("BEST_OBSERVED_MEDIAN"))q=choose("QUESTION",questions,ordinal);
            if(!a.method.equals("BEST_OBSERVED_MEDIAN"))a=choose("ANSWER",answers,ordinal);
            var qi=d.atTime(q.hour,0).atZone(YouTubeStoryWindows.ROME).toInstant();
            var ai=d.atTime(a.hour,0).atZone(YouTubeStoryWindows.ROME).toInstant();
            if(!qi.isAfter(earliest)||YouTubeStoryWindows.conflict(qi,releases).isPresent()||
                    YouTubeStoryWindows.conflict(ai,releases).isPresent())continue;
            if(first==null)first=d;
            candidates.add(new Plan(d,q,a,"EARLIEST_COMPATIBLE_INSUFFICIENT_EVIDENCE",evidence));
        }
        if(candidates.isEmpty())return Optional.empty();
        var eligible=candidates.stream().filter(p->supported.stream().anyMatch(e->e.weekday==p.day.getDayOfWeek())).toList();
        if(eligible.size()<2)return Optional.of(candidates.getFirst());
        var best=eligible.stream().max(Comparator.<Plan>comparingDouble(p->{
            var e=supported.stream().filter(x->x.weekday==p.day.getDayOfWeek()).findFirst().orElseThrow();
            return e.questionMedian/2+e.answerMedian/2;
        }).thenComparing(Plan::day,Comparator.reverseOrder())).orElseThrow();
        return Optional.of(new Plan(best.day,best.question,best.answer,"BEST_OBSERVED_WEEKDAY_MEDIAN",evidence));
    }

}
