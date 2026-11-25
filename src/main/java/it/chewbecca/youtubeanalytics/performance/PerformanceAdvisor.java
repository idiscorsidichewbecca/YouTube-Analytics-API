package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.client.YouTubeAnalyticsClient;
import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.service.ShortsClassifier;
import it.chewbecca.youtubeanalytics.service.YoutubeCatalogService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static it.chewbecca.youtubeanalytics.client.YouTubeDataClient.VideoMetadata;

@Service
public class PerformanceAdvisor {
    static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final Set<String> STOP = Set.of("della", "delle", "dello", "nella", "tutti", "saga", "shorts",
            "star", "wars", "chewbecca", "come", "cosa", "storia", "questo", "dentro", "esploriamo");
    private final YouTubeAnalyticsClient analytics;
    private final YouTubeDataClient data;
    private final YoutubeCatalogService catalog;
    private final GoogleAccessTokenService tokens;
    private final ShortsClassifier formats;
    private final ReachRepository reach;
    private final EndScreenReportingRepository endScreens;
    private final Clock clock;

    public PerformanceAdvisor(YouTubeAnalyticsClient analytics, YouTubeDataClient data, YoutubeCatalogService catalog,
                              GoogleAccessTokenService tokens, ShortsClassifier formats, ReachRepository reach,
                              EndScreenReportingRepository endScreens, Clock clock) {
        this.analytics = analytics;
        this.data = data;
        this.catalog = catalog;
        this.tokens = tokens;
        this.formats = formats;
        this.reach = reach;
        this.endScreens = endScreens;
        this.clock = clock;
    }

    public Detail metrics(String id, LocalDate from, LocalDate to, List<String> sections) {
        var video = owned(id);
        var range = window(video, from, to);
        return metrics(video, range, sections);
    }

    private Detail metrics(VideoMetadata video, Window range, List<String> sections) {
        String id = video.videoId();
        Map<String, Observation> result = new LinkedHashMap<>();
        var selected = sections.isEmpty() ? List.of("CORE", "ENGAGEMENT", "DAILY", "REACH", "TRAFFIC") : sections;
        for (String section : selected) {
            switch (section) {
                case "REACH" -> {
                    var observed = reach.reach(channel(), id, range.from(), range.to());
                    var last = observed.daily().isEmpty() ? null : observed.daily().getLast().date();
                    result.put(section, new Observation(observed.source(), range, clock.instant(), last,
                            observed.availability(), observed));
                }
                case "TRAFFIC" -> {
                    var sources = analytics.getTrafficSources(id, range.from(), range.to());
                    result.put(section, observation(range, sources, null));
                }
                case "SEARCH_TERMS", "SUGGESTED_VIDEOS", "EXTERNAL_DETAILS" -> {
                    String source = switch (section) {
                        case "SEARCH_TERMS" -> "YT_SEARCH";
                        case "SUGGESTED_VIDEOS" -> "RELATED_VIDEO";
                        default -> "EXT_URL";
                    };
                    result.put(section, optional(range,
                            () -> analytics.getTrafficSourceDetails(id, source, range.from(), range.to(), 20)));
                }
                case "RETENTION" -> result.put(section, optional(range, () -> {
                    var rows = rows(id, range, "RETENTION");
                    return rows.isEmpty() ? List.of() : retention(rows, video.durationSeconds());
                }));
                case "DAILY" -> {
                    var trend = rows(id, range, section);
                    LocalDate latest = trend.isEmpty() ? null : LocalDate.parse(trend.getLast().get("day").toString());
                    result.put(section, observation(range, trend, latest));
                }
                case "MONETARY", "ADS" -> {
                    var scopes = tokens.requireCredential().getScopes();
                    if (scopes == null || Arrays.stream(scopes.split("\\s+"))
                            .noneMatch(it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService.MONETARY_ANALYTICS_SCOPE::equals)) {
                        result.put(section, new Observation("YouTube Analytics API", range, clock.instant(), null,
                                "UNAUTHORIZED", null));
                    } else {
                        result.put(section, optional(range, () -> rows(id, range, section)));
                    }
                }
                case "END_SCREENS" -> {
                    var totals = endScreens.totals(channel(), id, range.from(), range.to());
                    var last = totals.daily().isEmpty() ? null : totals.daily().getLast().date();
                    result.put(section, new Observation("YouTube Reporting API/channel_end_screens_a2", range,
                            clock.instant(), last, totals.availability(), totals));
                }
                case "ENGAGED_VIEWS" -> result.put(section, optional(range, () -> rows(id, range, section)));
                case "CORE", "ENGAGEMENT" -> result.put(section, observation(range, rows(id, range, section), null));
                default -> throw new IllegalArgumentException("Sezione performance sconosciuta: " + section);
            }
        }
        return new Detail(view(video), range, result, List.of(
                "Date giornaliere Google in Pacific Time. L'ultimo giorno effettivo è indicato quando osservabile.",
                "Visualizzazioni feed Shorts: sorgente SHORTS se restituita. Swiped away non disponibile in queste API.",
                "Formule editoriali facoltative: (like + commenti + condivisioni) / views; iscritti acquisiti / views; RPM stimato: ricavo stimato / views × 1000. Usa la stessa finestra e solo valori disponibili."));
    }

    public Benchmark benchmark(String id, int ageDays) {
        var video = owned(id);
        if (!List.of(1, 2, 7, 28).contains(ageDays)) throw new IllegalArgumentException("Finestra ammessa: 1, 2, 7 o 28 giorni");
        return benchmark(video, ageDays, catalog.videos());
    }

    private Benchmark benchmark(VideoMetadata video, int ageDays, List<VideoMetadata> candidates) {
        var targetWindow = age(video, ageDays);
        if (targetWindow.to().isAfter(lastConservativeDay())) {
            return new Benchmark(ageDays, targetWindow, "INSUFFICIENT_DATA", List.of(), Map.of(), null,
                    "La finestra dal giorno di pubblicazione è ancora incompleta o nel ritardo di elaborazione.");
        }
        var target = sample(video, targetWindow, true);
        String owner = channel();
        var possible = candidates.stream().filter(v -> eligible(v, owner) && !v.videoId().equals(video.videoId())
                        && formats.classify(v).type() == formats.classify(video).type()
                        && v.publishedAt().isBefore(video.publishedAt())
                        && !age(v, ageDays).to().isAfter(lastConservativeDay())
                        && v.durationSeconds() >= video.durationSeconds() * 0.55
                        && v.durationSeconds() <= video.durationSeconds() * 1.85)
                .sorted(Comparator.comparingDouble((VideoMetadata candidate) -> match(video, candidate)).reversed())
                .limit(8).toList();
        var base = new ArrayList<Sample>();
        for (var candidate : possible) {
            var row = sample(candidate, age(candidate, ageDays), true);
            if (row.views() != null) base.add(new Sample(row.video(), row.views(), row.watchMinutes(),
                    row.averageViewPercentage(), row.impressions(), row.ctr(), row.at30(), row.at60(),
                    row.subscriberConversion(), row.mainTraffic(), null));
        }
        var sameTraffic = target.mainTraffic() == null ? List.<Sample>of() : base.stream()
                .filter(row -> target.mainTraffic().equals(row.mainTraffic())).toList();
        var cohort = sameTraffic.size() >= 3 ? sameTraffic : List.copyOf(base);
        Map<String, Distribution> benchmarks = new LinkedHashMap<>();
        distribution(cohort, Sample::views, "views", benchmarks);
        distribution(cohort, Sample::impressions, "impressions", benchmarks);
        distribution(cohort, Sample::ctr, "ctr", benchmarks);
        distribution(cohort, Sample::watchMinutes, "watchMinutes", benchmarks);
        distribution(cohort, Sample::at30, "at30", benchmarks);
        distribution(cohort, Sample::at60, "at60", benchmarks);
        distribution(cohort, Sample::subscriberConversion, "subscriberConversion", benchmarks);
        String confidence = cohort.size() >= 6 && sameTraffic.size() >= 3 ? "MEDIUM"
                : cohort.size() >= 3 ? "LOW" : "INSUFFICIENT_DATA";
        return new Benchmark(ageDays, targetWindow, confidence, cohort, benchmarks, target,
                "Giorni di calendario Pacific dalla pubblicazione, non finestre orarie esatte. Selezione per formato, durata, termini di titolo/tag, serie, recenza e stagione. Sorgente prevalente allineata quando almeno tre video la condividono.");
    }

    public Diagnosis analyze(String id, int ageDays) {
        var benchmark = benchmark(id, ageDays);
        var target = benchmark.target();
        var hypotheses = new ArrayList<Hypothesis>();
        var stats = benchmark.percentiles();
        if (target != null && benchmark.peers().size() >= 3) {
            if (above(target.impressions(), stats.get("impressions"), .5) && below(target.ctr(), stats.get("ctr"))) {
                hypotheses.add(new Hypothesis("A", "HIGH", "Valuta titolo, copertina e pubblico raggiunto insieme.",
                        evidence(target, stats, "impressions", "ctr"), benchmark.confidence()));
            }
            if (above(target.ctr(), stats.get("ctr"), .5) && below(target.at30(), stats.get("at30"))) {
                hypotheses.add(new Hypothesis("B", "HIGH", "Verifica la promessa editoriale e i primi 30 secondi.",
                        evidence(target, stats, "ctr", "at30"), benchmark.confidence()));
            }
            if (above(target.ctr(), stats.get("ctr"), .5) && above(target.at30(), stats.get("at30"), .5)
                    && below(target.impressions(), stats.get("impressions"))) {
                hypotheses.add(new Hypothesis("C", "MEDIUM", "Esamina distribuzione, sorgenti e interesse per il tema.",
                        evidence(target, stats, "ctr", "at30", "impressions"), benchmark.confidence()));
            }
            if (above(target.at30(), stats.get("at30"), .5) && target.retention() != null
                    && target.retention().largestDrop() != null && target.retention().largestDrop().fromSeconds() >= 30
                    && standsOut(target.retention())) {
                hypotheses.add(new Hypothesis("D", "MEDIUM", "Rivedi il segmento di abbandono per i prossimi montaggi.",
                        Map.of("at30", target.at30(), "segment", target.retention().largestDrop()), "LOW"));
            }
            if (above(target.views(), stats.get("views"), .5)
                    && below(target.subscriberConversion(), stats.get("subscriberConversion"))) {
                hypotheses.add(new Hypothesis("E", "MEDIUM", "Valuta invito all'iscrizione, coerenza della serie e schermate finali.",
                        evidence(target, stats, "views", "subscriberConversion"), benchmark.confidence()));
            }
        }
        return new Diagnosis(view(owned(id)), benchmark, hypotheses, hypotheses.isEmpty()
                ? "Nessuna ipotesi supportata dai confronti disponibili." : "Ipotesi osservative da verificare con il contesto.");
    }

    public Audit audit(String kind, LocalDate from, LocalDate to, int page, int limit, Set<String> cooling) {
        if (!List.of("VIDEO", "SHORT").contains(kind) || page < 1 || limit < 1 || limit > 35) {
            throw new IllegalArgumentException("Formato VIDEO o SHORT, pagina positiva, limite da 1 a 35");
        }
        Window range = valid(from, to);
        String owner = channel();
        var inventory = catalog.videos();
        var videos = inventory.stream().filter(v -> eligible(v, owner) && !published(v).isBefore(range.from())
                && !published(v).isAfter(range.to()) && format(v).equals(kind)).toList();
        var ids = videos.stream().map(VideoMetadata::videoId).toList();
        LocalDate actualEnd = range.to().isAfter(lastConservativeDay()) ? lastConservativeDay() : range.to();
        var current = actualEnd.isBefore(range.from()) ? Map.<String, Long>of()
                : analytics.advisorViewTotals(ids, range.from(), actualEnd);
        LocalDate recentStart = actualEnd.minusDays(6);
        var recent = recentStart.isBefore(range.from()) ? current : analytics.advisorViewTotals(ids, recentStart, actualEnd);
        var earlier = recentStart.minusDays(7).isBefore(range.from()) ? Map.<String, Long>of()
                : analytics.advisorViewTotals(ids, recentStart.minusDays(7), recentStart.minusDays(1));
        var comparable = inventory.stream().filter(v -> eligible(v, owner) && format(v).equals(kind)
                && !published(v).plusDays(6).isAfter(lastConservativeDay()))
                .sorted(Comparator.comparing(VideoMetadata::publishedAt).reversed()).limit(250).toList();
        Map<String, ReachRepository.Reach> firstWeek = new HashMap<>();
        for (var v : comparable) firstWeek.put(v.videoId(), reach.reach(owner, v.videoId(), published(v), published(v).plusDays(6)));
        var ranked = videos.stream().map(v -> {
            var metrics = current.get(v.videoId());
            var latest = recent.get(v.videoId());
            var observed = firstWeek.get(v.videoId());
            var peerCtRs = comparable.stream().filter(candidate -> !candidate.videoId().equals(v.videoId())
                            && candidate.durationSeconds() >= v.durationSeconds() * .55
                            && candidate.durationSeconds() <= v.durationSeconds() * 1.85)
                    .sorted(Comparator.comparingDouble((VideoMetadata candidate) -> match(v, candidate)).reversed())
                    .map(candidate -> firstWeek.get(candidate.videoId()))
                    .filter(r -> r.ctr() != null && "AVAILABLE".equals(r.ctrAvailability()))
                    .limit(12).map(ReachRepository.Reach::ctr).sorted().toList();
            double score = 0;
            boolean comparableData = observed != null && "AVAILABLE".equals(observed.ctrAvailability()) && peerCtRs.size() >= 3;
            Double median = peerCtRs.size() >= 3 ? percentile(peerCtRs, .5) : null;
            Double p25 = peerCtRs.size() >= 3 ? percentile(peerCtRs, .25) : null;
            if (comparableData && observed.ctr() < median) {
                long previousViews = earlier.getOrDefault(v.videoId(), 0L);
                long recentViews = latest == null ? 0 : latest;
                double trendFactor = latest != null && previousViews > 0 && recentViews < previousViews
                        ? 1 + .2 * (1 - (double) recentViews / previousViews) : 1;
                score = Math.log1p(observed.impressions()) * (median - observed.ctr())
                        * Math.min(1, peerCtRs.size() / 8.0) * trendFactor;
            }
            boolean inCooldown = cooling.contains(v.videoId());
            return new Ranked(view(v), inCooldown ? 0 : score, peerCtRs.size(), median, p25, observed == null
                    ? null : observed.impressions(), observed == null ? null : observed.ctr(),
                    metrics, latest, earlier.get(v.videoId()),
                    inCooldown ? "COOLDOWN" : comparableData ? "REVIEW" : "INSUFFICIENT_DATA");
        }).sorted(Comparator.comparingDouble(Ranked::priorityScore).reversed().thenComparing(
                Ranked::periodViews, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.video().videoId())).toList();
        var offset = (page - 1L) * limit;
        return new Audit(range, actualEnd.isBefore(range.from()) ? null : actualEnd, kind,
                ranked.size(), page, offset + limit < ranked.size(), ranked.stream()
                .skip(offset).limit(limit).toList(),
                "Priorità di approfondimento basata sull'opportunità di CTR nella prima settimana, copertura, campione e cooldown. Le visualizzazioni recenti danno contesto e ordinamento secondario.");
    }

    public List<WeeklyLeader> leaders(LocalDate from, LocalDate to, int limit) {
        LocalDate end = to.isAfter(lastConservativeDay()) ? lastConservativeDay() : to;
        if (end.isBefore(from)) return List.of();
        String owner = channel();
        var candidates = catalog.videos().stream()
                .filter(video -> eligible(video, owner) && !published(video).isAfter(end)).toList();
        var views = analytics.advisorViewTotals(candidates.stream().map(VideoMetadata::videoId).toList(), from, end);
        return candidates.stream().filter(video -> views.containsKey(video.videoId()))
                .map(video -> new WeeklyLeader(view(video), views.get(video.videoId()), new Window(from, end)))
                .sorted(Comparator.comparingLong(WeeklyLeader::views).reversed())
                .limit(limit).toList();
    }

    public ComparisonSnapshot snapshot(String id, Window window) {
        var video = owned(id);
        return new ComparisonSnapshot(window, clock.instant(), sample(video, window, false));
    }

    private Sample sample(VideoMetadata video, Window window, boolean extended) {
        String id = video.videoId();
        var reports = rows(id, window, "CORE");
        var core = reports.isEmpty() ? Map.<String, Object>of() : reports.getFirst();
        var engagement = extended ? rows(id, window, "ENGAGEMENT") : List.<Map<String, Object>>of();
        var impressions = reach.reach(channel(), id, window.from(), window.to());
        var ret = optional(window, () -> {
            var rr = rows(id, window, "RETENTION");
            return rr.isEmpty() ? List.of() : retention(rr, video.durationSeconds());
        });
        Retention retention = ret.data() instanceof Retention value ? value : null;
        String traffic = extended ? analytics.getTrafficSources(id, window.from(), window.to()).stream()
                .max(Comparator.comparingLong(YouTubeAnalyticsClient.BreakdownMetric::views))
                .map(YouTubeAnalyticsClient.BreakdownMetric::dimensionValue).orElse(null) : null;
        Double views = number(core, "views");
        Double subs = engagement.isEmpty() ? null : number(engagement.getFirst(), "subscribersGained");
        return new Sample(view(video), views, number(core, "estimatedMinutesWatched"),
                number(core, "averageViewPercentage"),
                "AVAILABLE".equals(impressions.availability()) ? impressions.impressions() : null,
                "AVAILABLE".equals(impressions.ctrAvailability()) ? impressions.ctr() : null,
                retention == null ? null : retention.at30(), retention == null ? null : retention.at60(),
                subs == null || views == null || views <= 0 ? null : subs / views, traffic, retention);
    }

    private Observation optional(Window window, java.util.function.Supplier<?> get) {
        try {
            return observation(window, get.get(), null);
        } catch (GoogleApiException error) {
            if (error.getStatusCode() == 400 || error.getStatusCode() == 403
                    && !it.chewbecca.youtubeanalytics.client.YouTubeStudioClient.quota(error)) {
                return new Observation("YouTube Analytics API", window, clock.instant(), null,
                        "UNAVAILABLE_OR_UNSUPPORTED", null);
            }
            throw error;
        }
    }

    private Observation observation(Window window, Object value, LocalDate latest) {
        boolean empty = value instanceof Collection<?> collection && collection.isEmpty();
        String state = empty ? "NO_ROWS" : window.to().isAfter(lastConservativeDay()) ? "MAY_BE_PARTIAL" : "RETURNED";
        if (latest != null && latest.isBefore(window.to())) state = "PARTIAL";
        return new Observation("YouTube Analytics API", window, clock.instant(), latest, state, empty ? null : value);
    }

    private List<Map<String, Object>> rows(String id, Window window, String report) {
        return analytics.advisorRows(id, window.from(), window.to(), report);
    }

    static Retention retention(List<Map<String, Object>> rows, long duration) {
        List<Segment> points = rows.stream().filter(row -> number(row, "elapsedVideoTimeRatio") != null)
                .map(row -> new Segment(number(row, "elapsedVideoTimeRatio") * duration,
                        number(row, "audienceWatchRatio"), number(row, "relativeRetentionPerformance")))
                .sorted(Comparator.comparingDouble(Segment::seconds)).toList();
        Drop largest = null;
        for (int i = 0; i + 1 < points.size(); i++) {
            var left = points.get(i);
            var right = points.get(i + 1);
            if (left.watchRatio() == null || right.watchRatio() == null) continue;
            double delta = left.watchRatio() - right.watchRatio();
            if (delta > 0 && (largest == null || delta > largest.drop())) {
                largest = new Drop(left.seconds(), right.seconds(), delta);
            }
        }
        return new Retention(at(points, duration, 30), at(points, duration, 60), largest,
                points.stream().filter(p -> p.watchRatio() != null && p.watchRatio() > 1)
                        .sorted(Comparator.comparingDouble(Segment::watchRatio).reversed()).limit(5).toList(), points);
    }

    private static boolean standsOut(Retention retention) {
        var dropSizes = new ArrayList<Double>();
        for (int i = 1; i < retention.curve().size(); i++) {
            var previous = retention.curve().get(i - 1).watchRatio();
            var next = retention.curve().get(i).watchRatio();
            if (previous != null && next != null && previous > next) dropSizes.add(previous - next);
        }
        dropSizes.sort(Comparator.naturalOrder());
        return dropSizes.size() >= 5 && retention.largestDrop().drop() > 2 * percentile(dropSizes, .75);
    }

    private static Double at(List<Segment> points, long duration, int seconds) {
        if (seconds >= duration) return null;
        return points.stream().filter(segment -> Math.abs(segment.seconds() - seconds) <= duration * .011
                        && segment.watchRatio() != null)
                .min(Comparator.comparingDouble(segment -> Math.abs(segment.seconds() - seconds)))
                .map(Segment::watchRatio).orElse(null);
    }

    private static boolean below(Number target, Distribution stats) {
        return target != null && stats != null && stats.sample() >= 3 && target.doubleValue() < stats.p25();
    }

    private static boolean above(Number target, Distribution stats, double percentile) {
        if (target == null || stats == null || stats.sample() < 3) return false;
        return target.doubleValue() >= (percentile == .5 ? stats.median() : stats.p75());
    }

    private static Map<String, Object> evidence(Sample target, Map<String, Distribution> stats, String... fields) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (String field : fields) {
            Number observed = switch (field) {
                case "impressions" -> target.impressions();
                case "ctr" -> target.ctr();
                case "at30" -> target.at30();
                case "subscriberConversion" -> target.subscriberConversion();
                default -> target.views();
            };
            output.put(field, Map.of("video", observed, "benchmark", stats.get(field)));
        }
        return output;
    }

    private static void distribution(List<Sample> sample, java.util.function.Function<Sample, Number> fetch,
                                     String name, Map<String, Distribution> result) {
        var numbers = sample.stream().map(fetch).filter(Objects::nonNull).map(Number::doubleValue).sorted().toList();
        if (!numbers.isEmpty()) result.put(name, new Distribution(numbers.size(), percentile(numbers, .25),
                percentile(numbers, .5), percentile(numbers, .75)));
    }

    static double percentile(List<Double> sorted, double fraction) {
        double index = fraction * (sorted.size() - 1);
        int left = (int) index;
        return sorted.get(left) + (index - left) * (sorted.get((int) Math.ceil(index)) - sorted.get(left));
    }

    private double match(VideoMetadata target, VideoMetadata candidate) {
        var terms = tokens(target);
        var other = tokens(candidate);
        long overlap = terms.stream().filter(other::contains).count();
        double similarity = 4.0 * overlap / Math.max(1, terms.size() + other.size() - overlap);
        double duration = Math.abs(Math.log((double) target.durationSeconds() / candidate.durationSeconds()));
        long months = Math.abs(ChronoUnit.MONTHS.between(published(target), published(candidate)));
        int monthGap = Math.abs(published(target).getMonthValue() - published(candidate).getMonthValue());
        return similarity - duration - Math.min(24, months) * .03
                - Math.min(monthGap, 12 - monthGap) * .06
                + (editorial(target).equals(editorial(candidate)) ? .8 : 0);
    }

    private static String editorial(VideoMetadata video) {
        String title = video.title().toLowerCase(Locale.ROOT);
        if (title.contains("esplor") || title.contains("tour")) return "TOUR";
        if (title.contains("confront") || title.contains(" versus ")) return "CONFRONTO";
        if (title.contains("battaglia") || title.contains("guerra")) return "BATTAGLIA";
        if (title.contains("lo sapevi") || title.contains("curiosit")) return "CURIOSITA";
        return "STORIA_O_ALTRO";
    }

    private static Set<String> tokens(VideoMetadata video) {
        String source = video.title() + " " + String.join(" ", video.tags());
        String plain = Normalizer.normalize(source, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        var result = new HashSet<String>();
        for (String word : plain.split("[^a-z0-9]+")) {
            if (word.length() >= 4 && !STOP.contains(word)) result.add(word);
        }
        return result;
    }

    VideoMetadata owned(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("ID video non valido");
        var video = data.getVideo(id);
        if (video == null || !channel().equals(video.channelId())) throw new IllegalArgumentException("Video del canale non trovato");
        return video;
    }

    public String channel() { return tokens.requireCredential().getChannelId(); }

    LocalDate lastConservativeDay() { return LocalDate.now(clock.withZone(PACIFIC)).minusDays(3); }

    private static boolean eligible(VideoMetadata video, String owner) {
        return video.available() && owner.equals(video.channelId()) && video.publishedAt() != null
                && video.durationSeconds() > 0 && video.scheduledPublishAt() == null
                && !"private".equals(video.privacyStatus());
    }

    private LocalDate published(VideoMetadata video) { return video.publishedAt().atZone(PACIFIC).toLocalDate(); }

    private Window age(VideoMetadata video, int days) {
        if (video.publishedAt() == null || video.scheduledPublishAt() != null || "private".equals(video.privacyStatus())) {
            throw new IllegalArgumentException("Confronto per età disponibile per video pubblicati");
        }
        LocalDate day = published(video);
        return new Window(day, day.plusDays(days - 1L));
    }

    private Window window(VideoMetadata video, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(PACIFIC)) : to;
        LocalDate start = from == null ? end.minusDays(27) : from;
        if (from == null && video.publishedAt() != null && published(video).isAfter(start)
                && !published(video).isAfter(end)) start = published(video);
        return valid(start, end);
    }

    private Window valid(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.plusDays(365).isBefore(to)
                || to.isAfter(LocalDate.now(clock.withZone(PACIFIC)))) {
            throw new IllegalArgumentException("Fornisci date valide, intervallo massimo 366 giorni e nessuna data futura");
        }
        return new Window(from, to);
    }

    String format(VideoMetadata video) {
        return formats.classify(video).type() == ContentType.SHORT ? "SHORT" : "VIDEO";
    }

    private VideoView view(VideoMetadata video) {
        var classification = formats.classify(video);
        return new VideoView(video.videoId(), video.title(), video.thumbnailUrl(), format(video),
                classification.confidence(), video.durationSeconds(), video.publishedAt(), editorial(video));
    }

    private static Double number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number number ? number.doubleValue() : null;
    }

    public record Window(LocalDate from, LocalDate to) {}
    public record VideoView(String videoId, String title, String thumbnailUrl, String format,
                            String formatConfidence, long durationSeconds, Instant publishedAt, String editorialType) {}
    public record Observation(String source, Window requestedPeriod, Instant fetchedAt, LocalDate lastObservedDay,
                              String availability, Object data) {}
    public record Detail(VideoView video, Window period, Map<String, Observation> metrics, List<String> notes) {}
    public record Segment(double seconds, Double watchRatio, Double relativeRetention) {}
    public record Drop(double fromSeconds, double toSeconds, double drop) {}
    public record Retention(Double at30, Double at60, Drop largestDrop, List<Segment> replayed, List<Segment> curve) {}
    public record Sample(VideoView video, Double views, Double watchMinutes, Double averageViewPercentage, Long impressions,
                         Double ctr, Double at30, Double at60, Double subscriberConversion, String mainTraffic, Retention retention) {}
    public record Distribution(int sample, double p25, double median, double p75) {}
    public record Benchmark(int ageDays, Window targetPeriod, String confidence, List<Sample> peers,
                            Map<String, Distribution> percentiles, Sample target, String method) {}
    public record Hypothesis(String code, String priority, String action, Map<String, Object> evidence, String confidence) {}
    public record Diagnosis(VideoView video, Benchmark benchmark, List<Hypothesis> hypotheses, String note) {}
    public record Ranked(VideoView video, double priorityScore, int peerCount, Double benchmarkCtrMedian,
                         Double benchmarkCtrP25, Long firstWeekImpressions, Double firstWeekCtr,
                         Long periodViews, Long recentViews, Long precedingWeekViews, String evidence) {}
    public record Audit(Window publicationPeriod, LocalDate conservativeViewsThrough, String format,
                        int total, int page, boolean hasNext,
                        List<Ranked> videos, String method) {}
    public record WeeklyLeader(VideoView video, long views, Window period) {}
    public record ComparisonSnapshot(Window period, Instant fetchedAt, Sample sample) {}
}
