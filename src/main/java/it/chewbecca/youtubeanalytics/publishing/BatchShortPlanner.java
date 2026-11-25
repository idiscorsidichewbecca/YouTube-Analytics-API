package it.chewbecca.youtubeanalytics.publishing;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static it.chewbecca.youtubeanalytics.publishing.WeeklyShortPlanner.ROME;

@Service
public class BatchShortPlanner {
    public static final int MAX_BATCH_SIZE = 10;
    private static final int WEEKLY_LIMIT = 3;

    private final WeeklyShortPlanner historyPlanner;
    private final YouTubePublicationCalendar calendar;
    private final ShortDraftRepository drafts;
    private final Clock clock;

    public BatchShortPlanner(WeeklyShortPlanner historyPlanner, YouTubePublicationCalendar calendar,
                             ShortDraftRepository drafts, Clock clock) {
        this.historyPlanner = historyPlanner;
        this.calendar = calendar;
        this.drafts = drafts;
        this.clock = clock;
    }

    public Plan plan(int size, boolean startNextWeek) {
        if (size < 1 || size > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Un batch accetta da 1 a 10 Shorts");
        }
        Instant now = clock.instant();
        LocalDate firstWeek = monday(now.atZone(ROME).toLocalDate());
        if (startNextWeek) firstWeek = firstWeek.plusWeeks(1);
        Instant earliest = now.plus(Duration.ofHours(2));
        Instant latest = now.plus(Duration.ofDays(365));
        var events = events(startOf(firstWeek), latest, null);
        var history = historyPlanner.publicationHistory(now);
        List<WeeklyShortPlanner.SlotEvidence> selections = new ArrayList<>();
        for (LocalDate week = firstWeek; startOf(week).isBefore(latest); week = week.plusWeeks(1)) {
            LocalDate weekStart = week;
            List<Event> thisWeek = events.stream().filter(event -> monday(event.day()).equals(weekStart)).toList();
            int available = Math.min(remainingCapacity(thisWeek), size - selections.size());
            if (available <= 0) continue;
            List<ZonedDateTime> times = availableTimes(week, earliest, latest, thisWeek);
            var ranked = historyPlanner.rankSlots(times, history);
            Map<LocalDate, ShortPublicationPlanner.Slot> bestByDay = new LinkedHashMap<>();
            for (var slot : ranked) {
                bestByDay.putIfAbsent(slot.dateTime().toLocalDate(), slot);
            }
            bestByDay.values().stream().limit(available)
                    .sorted(Comparator.comparing(ShortPublicationPlanner.Slot::dateTime))
                    .map(slot -> WeeklyShortPlanner.evidenceFor(slot.dateTime(), history.videos(), history.hasEngagedViews()))
                    .forEach(selections::add);
            if (selections.size() == size) {
                return new Plan(List.copyOf(selections), history.videos().size(), history.truncated());
            }
        }
        throw new IllegalStateException("Capacità insufficiente per l'intero batch entro il limite di programmazione");
    }

    void assertAvailable(Instant proposed, String draftId) {
        LocalDate day = proposed.atZone(ROME).toLocalDate();
        LocalDate week = monday(day);
        List<Event> events = events(startOf(week), startOf(week.plusWeeks(1)), draftId);
        if (events.stream().anyMatch(event -> event.day().equals(day))) {
            throw new IllegalStateException("Giornata già occupata in ora italiana");
        }
        if (remainingCapacity(events) <= 0) {
            throw new IllegalStateException("La settimana ha già tre Shorts pubblicati o programmati");
        }
    }

    private List<Event> events(Instant from, Instant until, String excludedDraftId) {
        Map<String, Event> events = new HashMap<>();
        for (var publication : calendar.between(from, until)) {
            events.put(publication.videoId(),
                    new Event(publication.publishAt().atZone(ROME).toLocalDate(), publication.countsAsShort()));
        }
        for (var draft : drafts.findReservations(from, until, ShortDraft.Status.DRAFT)) {
            if (draft.getId().equals(excludedDraftId)) continue;
            String videoId = draft.getYoutubeVideoId();
            LocalDate day = draft.getPublishAt().atZone(ROME).toLocalDate();
            if (videoId == null) {
                events.put("draft:" + draft.getId(), new Event(day, true));
            } else {
                events.merge(videoId, new Event(day, true), (youtube, local) -> new Event(youtube.day(), true));
            }
        }
        return List.copyOf(events.values());
    }

    private static int remainingCapacity(List<Event> events) {
        return WEEKLY_LIMIT - (int) events.stream().filter(Event::countsAsShort).count();
    }

    private static List<ZonedDateTime> availableTimes(LocalDate week, Instant earliest, Instant latest,
                                                       List<Event> events) {
        Set<LocalDate> busyDays = new HashSet<>();
        events.forEach(event -> busyDays.add(event.day()));
        List<ZonedDateTime> times = new ArrayList<>();
        Instant until = startOf(week.plusWeeks(1));
        for (ZonedDateTime slot = week.atStartOfDay(ROME);
             slot.toInstant().isBefore(until) && slot.toInstant().isBefore(latest);
             slot = slot.plus(30, ChronoUnit.MINUTES)) {
            if (!slot.toInstant().isBefore(earliest) && !busyDays.contains(slot.toLocalDate())) {
                times.add(slot);
            }
        }
        return times;
    }

    private static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ROME).toInstant();
    }

    private record Event(LocalDate day, boolean countsAsShort) {}
    record Plan(List<WeeklyShortPlanner.SlotEvidence> slots, int videosAnalyzed, boolean historyTruncated) {}
}
