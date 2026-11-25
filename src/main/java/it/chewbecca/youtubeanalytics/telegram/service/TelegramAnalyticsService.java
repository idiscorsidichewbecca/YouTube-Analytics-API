package it.chewbecca.youtubeanalytics.telegram.service;

import it.chewbecca.youtubeanalytics.api.model.ApiModels;
import it.chewbecca.youtubeanalytics.api.model.ContentType;
import it.chewbecca.youtubeanalytics.api.model.SortBy;
import it.chewbecca.youtubeanalytics.api.model.SortOrder;
import it.chewbecca.youtubeanalytics.service.YoutubeReportService;
import it.chewbecca.youtubeanalytics.service.YoutubeRevenueService;
import it.chewbecca.youtubeanalytics.service.DateRangeResolver;
import it.chewbecca.youtubeanalytics.telegram.config.TelegramAnalyticsProperties;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramAnalyticsService {

    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern YEAR_MONTH = Pattern.compile("(?<!\\d)(\\d{4})-(0[1-9]|1[0-2])(?!-?\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<![\\d-])(20\\d{2})(?![\\d-])");
    private static final int TOP_LIMIT = 10;
    private static final int LATEST_VIDEO_COMPARISON_LIMIT = 10;

    private final YoutubeReportService reportService;
    private final YoutubeRevenueService revenueService;
    private final TelegramCommandCatalog commandCatalog;
    private final TelegramAnalyticsProperties properties;
    private final Clock clock;

    public TelegramAnalyticsService(YoutubeReportService reportService,
                                    YoutubeRevenueService revenueService,
                                    TelegramCommandCatalog commandCatalog,
                                    TelegramAnalyticsProperties properties,
                                    Clock clock) {
        this.reportService = reportService;
        this.revenueService = revenueService;
        this.commandCatalog = commandCatalog;
        this.properties = properties;
        this.clock = clock;
    }

    public String answer(String input, String botUsername) {
        try {
            CommandType command = resolve(input);
            return switch (command) {
                case PING -> "Bot attivo ✅";
                case HELP -> commandCatalog.helpText(botUsername);
                case CURRENT_STATUS -> currentStatus();
                case TOP_VIDEO -> topContent(ContentType.VIDEO, TOP_LIMIT, dateRange(input, 0, 2));
                case TOP_SHORT -> topContent(ContentType.SHORT, TOP_LIMIT, dateRange(input, 0, 2));
                case THREE_MONTHS -> threeMonths();
                case REVENUE_30 -> revenue(lastCompleteDays(30));
                case RESTARTS -> restarts();
                case COMPARE_MONTH -> compareMonth(input);
                case COMPARE_YEAR -> compareYear(input);
                case LATEST_VIDEO -> latestVideo();
                case UNKNOWN -> "Non ho riconosciuto la richiesta.\n\n"
                        + commandCatalog.helpText(botUsername);
            };
        } catch (CommandInputException ex) {
            return "Richiesta non valida: " + ex.getMessage() + "\n\n"
                    + commandCatalog.helpText(botUsername);
        }
    }

    private CommandType resolve(String input) {
        String normalized = normalize(input);
        String firstWord = normalized.split("\\s+", 2)[0];
        if (firstWord.startsWith("/")) {
            return switch (firstWord) {
                case "/ping" -> CommandType.PING;
                case "/help", "/comandi" -> CommandType.HELP;
                case "/adesso", "/summary", "/riepilogo" -> CommandType.CURRENT_STATUS;
                case "/topvideo", "/topvideos" -> CommandType.TOP_VIDEO;
                case "/topshort", "/topshorts" -> CommandType.TOP_SHORT;
                case "/trimestre", "/tremesi" -> CommandType.THREE_MONTHS;
                case "/guadagni30", "/revenue", "/ricavi" -> CommandType.REVENUE_30;
                case "/ripartenze" -> CommandType.RESTARTS;
                case "/confrontomese" -> CommandType.COMPARE_MONTH;
                case "/confrontoanno" -> CommandType.COMPARE_YEAR;
                case "/ultimovideo" -> CommandType.LATEST_VIDEO;
                default -> CommandType.UNKNOWN;
            };
        }

        if (normalized.equals("ping")) {
            return CommandType.PING;
        }
        if (containsAny(normalized, "cosa puoi fare", "comandi", "aiuto", "help")) {
            return CommandType.HELP;
        }
        if (normalized.contains("ripartenz")) {
            return CommandType.RESTARTS;
        }
        if (normalized.contains("ultimo video") && containsAny(normalized, "come sta andando", "confront", "statistic")) {
            return CommandType.LATEST_VIDEO;
        }
        if (normalized.contains("quest'anno") || normalized.contains("quest anno")) {
            return CommandType.COMPARE_YEAR;
        }
        if (normalized.contains("ultimo mese") && normalized.contains("confront")) {
            return CommandType.COMPARE_MONTH;
        }
        if (normalized.contains("3 mesi") || normalized.contains("tre mesi")) {
            return CommandType.THREE_MONTHS;
        }
        if (normalized.contains("top") && normalized.contains("short")) {
            return CommandType.TOP_SHORT;
        }
        if (normalized.contains("top") && normalized.contains("video")) {
            return CommandType.TOP_VIDEO;
        }
        if (containsAny(normalized, "quanto abbiamo guadagnato", "quanto ha guadagnato", "guadagni", "ricavi", "revenue")) {
            return CommandType.REVENUE_30;
        }
        if (containsAny(normalized, "come sta andando", "riepilogo", "andamento del canale", "summary")) {
            return CommandType.CURRENT_STATUS;
        }
        return CommandType.UNKNOWN;
    }

    private String currentStatus() {
        DateRange current = lastCompleteDays(28);
        DateRange previous = previousEqualRange(current);
        return compareChannelPeriods(
                "Come sta andando il canale adesso",
                current,
                previous,
                "Ultimi 28 giorni",
                "28 giorni precedenti"
        );
    }

    private String threeMonths() {
        YearMonth lastCompleteMonth = YearMonth.from(latestCompleteDay()).minusMonths(1);
        DateRange current = new DateRange(
                lastCompleteMonth.minusMonths(2).atDay(1),
                lastCompleteMonth.atEndOfMonth()
        );
        DateRange previous = new DateRange(
                lastCompleteMonth.minusMonths(5).atDay(1),
                lastCompleteMonth.minusMonths(3).atEndOfMonth()
        );
        return compareChannelPeriods(
                "Analisi degli ultimi tre mesi completi",
                current,
                previous,
                "Ultimi 3 mesi",
                "3 mesi precedenti"
        );
    }

    private String compareMonth(String input) {
        YearMonth requested = requestedMonth(input);
        YearMonth latest = YearMonth.from(latestCompleteDay()).minusMonths(1);
        DateRange current = monthRange(latest);
        DateRange previous = monthRange(requested);
        return compareChannelPeriods(
                "Confronto mensile",
                current,
                previous,
                latest.toString(),
                requested.toString()
        );
    }

    private String compareYear(String input) {
        int requestedYear = requestedYear(input);
        LocalDate currentEnd = latestCompleteDay();
        LocalDate currentStart = LocalDate.of(currentEnd.getYear(), 1, 1);
        LocalDate requestedEnd = MonthDay.from(currentEnd).atYear(requestedYear);
        DateRange current = new DateRange(currentStart, currentEnd);
        DateRange previous = new DateRange(LocalDate.of(requestedYear, 1, 1), requestedEnd);
        return compareChannelPeriods(
                "Confronto annuale a parità di giorni",
                current,
                previous,
                String.valueOf(currentEnd.getYear()),
                String.valueOf(requestedYear)
        );
    }

    private String restarts() {
        List<RestartDate> restarts = restartDates();
        if (restarts.size() < 2) {
            return "Il confronto delle ripartenze richiede almeno due date configurate in "
                    + "CHANNEL_RESTART_DATES. Formato: Nome|YYYY-MM-DD,Nome|YYYY-MM-DD";
        }
        int configuredDays = properties.restartWindowDays() > 0 ? properties.restartWindowDays() : 30;
        int days = restarts.stream()
                .mapToInt(restart -> (int) ChronoUnit.DAYS.between(restart.date(), latestCompleteDay()) + 1)
                .min()
                .orElse(configuredDays);
        days = Math.min(configuredDays, days);
        if (days < 1) {
            return "Non esiste ancora un giorno completo successivo all'ultima ripartenza configurata.";
        }
        List<RestartResult> results = new ArrayList<>();
        for (RestartDate restart : restarts) {
            LocalDate end = restart.date().plusDays(days - 1L);
            ApiModels.ChannelSummaryResponse response = reportService.channelSummary(restart.date(), end);
            results.add(new RestartResult(restart.name(), response));
        }
        RestartResult best = results.stream()
                .max(java.util.Comparator.comparingLong(result -> result.response().periodMetrics().views()))
                .orElseThrow();
        StringBuilder text = new StringBuilder("Confronto ripartenze\n")
                .append("Finestra: primi ").append(days).append(" giorni completi\n\n");
        int position = 1;
        for (RestartResult result : results.stream()
                .sorted(java.util.Comparator.comparingLong(
                        (RestartResult item) -> item.response().periodMetrics().views()).reversed())
                .toList()) {
            ApiModels.AnalyticsMetrics metrics = result.response().periodMetrics();
            text.append(position++).append(". ").append(result.name()).append("\n")
                    .append(period(result.response().period())).append("\n")
                    .append(integer(metrics.views())).append(" views · ")
                    .append(decimal(metrics.estimatedHoursWatched())).append(" ore · ")
                    .append(signed(metrics.netSubscribers())).append(" iscritti netti\n\n");
        }
        text.append("Ripartenza migliore per views: ").append(best.name());
        return text.toString();
    }

    private String latestVideo() {
        ApiModels.ContentListResponse recent = reportService.content(
                null,
                null,
                ContentType.VIDEO,
                null,
                0,
                LATEST_VIDEO_COMPARISON_LIMIT + 1,
                SortBy.PUBLISHED_AT,
                SortOrder.DESC
        );
        if (recent.items().size() < 2) {
            return "Servono almeno due video lunghi per eseguire il confronto.";
        }

        ApiModels.ContentPerformanceItem latest = recent.items().get(0);
        LocalDate latestPublished = latest.publishedAt()
                .atZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)
                .toLocalDate();
        long availableDays = ChronoUnit.DAYS.between(latestPublished, latestCompleteDay()) + 1;
        if (availableDays < 1) {
            return "L'ultimo video è troppo recente: YouTube non ha ancora reso disponibile un giorno completo di dati.";
        }

        ApiModels.AnalyticsMetrics latestMetrics = reportService.contentDetail(
                latest.videoId(),
                latestPublished,
                latestPublished.plusDays(availableDays - 1)
        ).periodMetrics();

        List<ApiModels.AnalyticsMetrics> previousMetrics = new ArrayList<>();
        int comparedVideos = Math.min(LATEST_VIDEO_COMPARISON_LIMIT, recent.items().size() - 1);
        for (int index = 1; index <= comparedVideos; index++) {
            ApiModels.ContentPerformanceItem previous = recent.items().get(index);
            LocalDate published = previous.publishedAt()
                    .atZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)
                    .toLocalDate();
            previousMetrics.add(reportService.contentDetail(
                    previous.videoId(),
                    published,
                    published.plusDays(availableDays - 1)
            ).periodMetrics());
        }

        double averageViews = previousMetrics.stream()
                .mapToLong(ApiModels.AnalyticsMetrics::views).average().orElse(0);
        double averageHours = previousMetrics.stream()
                .mapToDouble(ApiModels.AnalyticsMetrics::estimatedHoursWatched).average().orElse(0);
        double averagePercentage = previousMetrics.stream()
                .mapToDouble(ApiModels.AnalyticsMetrics::averageViewPercentage).average().orElse(0);
        double averageSubscribers = previousMetrics.stream()
                .mapToLong(ApiModels.AnalyticsMetrics::netSubscribers).average().orElse(0);
        long betterThan = previousMetrics.stream()
                .filter(metric -> latestMetrics.views() > metric.views()).count();
        int rank = (int) previousMetrics.stream()
                .filter(metric -> metric.views() > latestMetrics.views()).count() + 1;

        return "Andamento dell'ultimo video\n\n"
                + shortTitle(latest.title()) + "\n"
                + latest.youtubeUrl() + "\n\n"
                + "Confronto sui primi " + availableDays + " giorni completi dalla pubblicazione, contro "
                + comparedVideos + " video precedenti.\n\n"
                + comparison("Views", latestMetrics.views(), averageViews, false) + "\n"
                + comparison("Ore guardate", latestMetrics.estimatedHoursWatched(), averageHours, true) + "\n"
                + comparison("Percentuale media vista", latestMetrics.averageViewPercentage(), averagePercentage, true) + "\n"
                + comparison("Iscritti netti", latestMetrics.netSubscribers(), averageSubscribers, true) + "\n\n"
                + "Posizione per views: " + rank + " su " + (comparedVideos + 1) + "\n"
                + "Sta superando " + betterThan + " dei " + comparedVideos + " video precedenti.";
    }

    private String compareChannelPeriods(String title,
                                         DateRange current,
                                         DateRange previous,
                                         String currentLabel,
                                         String previousLabel) {
        ApiModels.ChannelSummaryResponse first = reportService.channelSummary(current.start(), current.end());
        ApiModels.ChannelSummaryResponse second = reportService.channelSummary(previous.start(), previous.end());
        ApiModels.AnalyticsMetrics a = first.periodMetrics();
        ApiModels.AnalyticsMetrics b = second.periodMetrics();
        return title + "\n\n"
                + currentLabel + ": " + period(first.period()) + "\n"
                + previousLabel + ": " + period(second.period()) + "\n\n"
                + comparison("Visualizzazioni", a.views(), b.views(), false) + "\n"
                + comparison("Views coinvolte", a.engagedViews(), b.engagedViews(), false) + "\n"
                + comparison("Ore guardate", a.estimatedHoursWatched(), b.estimatedHoursWatched(), true) + "\n"
                + comparison("Iscritti netti", a.netSubscribers(), b.netSubscribers(), false) + "\n"
                + comparison("Mi piace", a.likes(), b.likes(), false) + "\n"
                + comparison("Commenti", a.comments(), b.comments(), false) + "\n"
                + comparison("Durata media (secondi)", a.averageViewDurationSeconds(), b.averageViewDurationSeconds(), true)
                + "\n\nLettura: " + interpretation(a, b);
    }

    private String interpretation(ApiModels.AnalyticsMetrics current, ApiModels.AnalyticsMetrics previous) {
        double viewDelta = percentageDelta(current.views(), previous.views());
        double watchDelta = percentageDelta(current.estimatedHoursWatched(), previous.estimatedHoursWatched());
        double subscriberDelta = current.netSubscribers() - previous.netSubscribers();
        String reach = viewDelta >= 10 ? "la distribuzione è in crescita"
                : viewDelta <= -10 ? "la distribuzione è in calo"
                : "la distribuzione è sostanzialmente stabile";
        String depth = watchDelta > viewDelta + 5 ? "il tempo guardato cresce più delle views"
                : watchDelta < viewDelta - 5 ? "il tempo guardato è più debole rispetto alle views"
                : "tempo guardato e views si muovono in modo coerente";
        String subscribers = subscriberDelta > 0 ? "il saldo netto degli iscritti migliora"
                : subscriberDelta < 0 ? "il saldo netto degli iscritti peggiora"
                : "il saldo netto degli iscritti è invariato";
        return reach + "; " + depth + "; " + subscribers + ".";
    }

    private String topContent(ContentType type, int limit, DateRange range) {
        ApiModels.ContentListResponse response = reportService.content(
                range.start(), range.end(), type, null, 0, limit, SortBy.VIEWS, SortOrder.DESC);
        String label = type == ContentType.SHORT ? "Short" : "video";
        StringBuilder text = new StringBuilder("Top 10 ").append(label).append(" per visualizzazioni\n")
                .append(period(response.period())).append("\n\n");
        if (response.items().isEmpty()) {
            return text.append("Nessun contenuto disponibile nel periodo.").toString();
        }
        int position = 1;
        for (ApiModels.ContentPerformanceItem item : response.items()) {
            ApiModels.AnalyticsMetrics metrics = item.periodMetrics();
            text.append(position++).append(". ").append(shortTitle(item.title())).append("\n")
                    .append("Views: ").append(integer(metrics.views()))
                    .append(" · Engaged: ").append(integer(metrics.engagedViews()))
                    .append(" · Media vista: ").append(decimal(metrics.averageViewPercentage())).append("%\n")
                    .append(item.youtubeUrl()).append("\n\n");
        }
        return text.toString().trim();
    }

    private String revenue(DateRange range) {
        ApiModels.RevenueSummaryResponse response = revenueService.summary(range.start(), range.end(), "EUR");
        ApiModels.RevenueMetrics metrics = response.metrics();
        return "Ricavi YouTube stimati\n"
                + period(response.period()) + "\n\n"
                + "Ricavi stimati: " + money(metrics.estimatedRevenue(), response.currency()) + "\n"
                + "Da annunci: " + money(metrics.estimatedAdRevenue(), response.currency()) + "\n"
                + "Da YouTube Premium: " + money(metrics.estimatedPremiumRevenue(), response.currency()) + "\n"
                + "Views: " + integer(metrics.views()) + "\n"
                + "RPM stimato: " + money(metrics.estimatedRpm(), response.currency());
    }

    private String comparison(String label, double first, double second, boolean decimals) {
        double delta = first - second;
        String percentage = second == 0
                ? "n/d"
                : signedDecimal((delta / Math.abs(second)) * 100) + "%";
        String firstText = decimals ? decimal(first) : integer(Math.round(first));
        String secondText = decimals ? decimal(second) : integer(Math.round(second));
        String deltaText = decimals ? signedDecimal(delta) : signed(Math.round(delta));
        return label + ": " + firstText + " vs " + secondText
                + " → " + deltaText + " (" + percentage + ")";
    }

    private DateRange lastCompleteDays(int days) {
        LocalDate end = latestCompleteDay();
        return new DateRange(end.minusDays(days - 1L), end);
    }

    private DateRange previousEqualRange(DateRange current) {
        long days = ChronoUnit.DAYS.between(current.start(), current.end()) + 1;
        LocalDate end = current.start().minusDays(1);
        return new DateRange(end.minusDays(days - 1), end);
    }

    private DateRange monthRange(YearMonth month) {
        return new DateRange(month.atDay(1), month.atEndOfMonth());
    }

    private LocalDate latestCompleteDay() {
        return LocalDate.now(clock.withZone(DateRangeResolver.YOUTUBE_ANALYTICS_ZONE)).minusDays(1);
    }

    private YearMonth requestedMonth(String input) {
        Matcher numeric = YEAR_MONTH.matcher(input);
        YearMonth month;
        if (numeric.find()) {
            month = YearMonth.parse(numeric.group());
        } else {
            String normalized = normalize(input);
            Matcher yearMatcher = YEAR.matcher(normalized);
            if (!yearMatcher.find()) {
                throw new CommandInputException("indica il mese da confrontare nel formato YYYY-MM");
            }
            int year = Integer.parseInt(yearMatcher.group(1));
            String[] italianMonths = {
                    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
                    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
            };
            int monthNumber = 0;
            for (int index = 0; index < italianMonths.length; index++) {
                if (normalized.contains(italianMonths[index])) {
                    monthNumber = index + 1;
                    break;
                }
            }
            if (monthNumber == 0) {
                throw new CommandInputException("indica il mese da confrontare nel formato YYYY-MM");
            }
            month = YearMonth.of(year, monthNumber);
        }
        YearMonth latestCompleteMonth = YearMonth.from(latestCompleteDay()).minusMonths(1);
        if (month.isAfter(latestCompleteMonth)) {
            throw new CommandInputException("il mese indicato non è ancora completamente disponibile");
        }
        return month;
    }

    private int requestedYear(String input) {
        Matcher matcher = YEAR.matcher(input);
        if (!matcher.find()) {
            throw new CommandInputException("indica l'anno da confrontare nel formato YYYY");
        }
        int year = Integer.parseInt(matcher.group(1));
        int currentYear = latestCompleteDay().getYear();
        if (year < 2005 || year >= currentYear) {
            throw new CommandInputException("indica un anno precedente a " + currentYear);
        }
        return year;
    }

    private List<RestartDate> restartDates() {
        if (properties.restartDates() == null || properties.restartDates().isBlank()) {
            return List.of();
        }
        List<RestartDate> values = new ArrayList<>();
        for (String rawEntry : properties.restartDates().split(",")) {
            String[] parts = rawEntry.trim().split("\\|", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new CommandInputException(
                        "CHANNEL_RESTART_DATES deve usare il formato Nome|YYYY-MM-DD,Nome|YYYY-MM-DD");
            }
            try {
                LocalDate date = LocalDate.parse(parts[1].trim());
                if (date.isAfter(latestCompleteDay())) {
                    throw new CommandInputException("la ripartenza " + parts[0].trim() + " è nel futuro");
                }
                values.add(new RestartDate(parts[0].trim(), date));
            } catch (DateTimeParseException ex) {
                throw new CommandInputException("data della ripartenza non valida: " + parts[1].trim());
            }
        }
        return List.copyOf(values);
    }

    private double percentageDelta(double current, double previous) {
        return previous == 0 ? 0 : ((current - previous) / Math.abs(previous)) * 100;
    }

    private DateRange dateRange(String input, int minimumDates, int maximumDates) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher matcher = ISO_DATE.matcher(input);
        while (matcher.find()) {
            try {
                dates.add(LocalDate.parse(matcher.group()));
            } catch (DateTimeParseException ex) {
                throw new CommandInputException("data non valida: " + matcher.group());
            }
        }
        if (dates.size() < minimumDates || dates.size() > maximumDates || dates.size() == 1) {
            throw new CommandInputException("specifica zero oppure due date nel formato YYYY-MM-DD");
        }
        if (dates.isEmpty()) {
            return new DateRange(null, null);
        }
        validateRange(dates.get(0), dates.get(1));
        return new DateRange(dates.get(0), dates.get(1));
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new CommandInputException("la data iniziale non può essere successiva alla data finale");
        }
        if (end.isAfter(latestCompleteDay())) {
            throw new CommandInputException("la data finale non può superare l'ultimo giorno completo disponibile");
        }
        if (start.isBefore(LocalDate.of(2005, 2, 14))) {
            throw new CommandInputException("la data iniziale non può precedere la nascita di YouTube");
        }
    }

    private String period(ApiModels.ReportPeriod period) {
        return period.startDate() + " → " + period.endDate();
    }

    private String integer(long value) {
        return NumberFormat.getIntegerInstance(Locale.ITALY).format(value);
    }

    private String decimal(double value) {
        return String.format(Locale.ITALY, "%.2f", value);
    }

    private String signed(long value) {
        return value > 0 ? "+" + integer(value) : integer(value);
    }

    private String signedDecimal(double value) {
        String formatted = decimal(value);
        return value > 0 ? "+" + formatted : formatted;
    }

    private String money(double value, String currency) {
        return decimal(value) + " " + currency;
    }

    private String shortTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Senza titolo";
        }
        return title.length() <= 90 ? title : title.substring(0, 87) + "...";
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('’', '\'')
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private enum CommandType {
        PING,
        HELP,
        CURRENT_STATUS,
        TOP_VIDEO,
        TOP_SHORT,
        THREE_MONTHS,
        REVENUE_30,
        RESTARTS,
        COMPARE_MONTH,
        COMPARE_YEAR,
        LATEST_VIDEO,
        UNKNOWN
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }

    private record RestartDate(String name, LocalDate date) {
    }

    private record RestartResult(String name, ApiModels.ChannelSummaryResponse response) {
    }

    private static class CommandInputException extends RuntimeException {
        CommandInputException(String message) {
            super(message);
        }
    }
}
