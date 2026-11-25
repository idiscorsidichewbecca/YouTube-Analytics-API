package it.chewbecca.youtubeanalytics.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import it.chewbecca.youtubeanalytics.meta.api.model.MetaApiModels;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphApiException;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphClient;
import it.chewbecca.youtubeanalytics.meta.exception.MetaTokenExpiredException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Service
public class MetaInsightService {

    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z0-9_]{1,120}");
    private static final int MAX_METRICS = 25;

    private final MetaGraphClient graphClient;

    public MetaInsightService(MetaGraphClient graphClient) {
        this.graphClient = graphClient;
    }

    public InsightLoadResult load(String objectId,
                                  String accessToken,
                                  Collection<String> metrics,
                                  MetaApiModels.ReportPeriod period,
                                  String aggregationPeriod,
                                  String metricType,
                                  String breakdown,
                                  String timeframe,
                                  String platform) {
        List<String> normalizedMetrics = normalizeMetrics(metrics);
        List<MetaApiModels.InsightMetric> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String metric : normalizedMetrics) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("metric", metric);
            parameters.put("since", period.startDate().toString());
            parameters.put("until", period.endDate().plusDays(1).toString());
            putIfText(parameters, "period", aggregationPeriod);
            putIfText(parameters, "metric_type", metricType);
            putIfText(parameters, "breakdown", breakdown);
            putIfText(parameters, "timeframe", timeframe);
            try {
                JsonNode root = graphClient.get(
                        "/" + objectId + "/insights",
                        accessToken,
                        parameters,
                        platform + " insights metric=" + metric
                );
                root.path("data").forEach(item -> result.add(toMetric(item)));
            } catch (MetaTokenExpiredException ex) {
                throw ex;
            } catch (MetaGraphApiException ex) {
                warnings.add(metric + ": " + concise(ex));
            }
        }
        return new InsightLoadResult(List.copyOf(result), List.copyOf(warnings));
    }

    public InsightLoadResult loadLifetime(String objectId,
                                          String accessToken,
                                          Collection<String> metrics,
                                          String platform) {
        List<String> normalizedMetrics = normalizeMetrics(metrics);
        List<MetaApiModels.InsightMetric> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String metric : normalizedMetrics) {
            try {
                JsonNode root = graphClient.get(
                        "/" + objectId + "/insights",
                        accessToken,
                        Map.of("metric", metric),
                        platform + " insights metric=" + metric
                );
                root.path("data").forEach(item -> result.add(toMetric(item)));
            } catch (MetaTokenExpiredException ex) {
                throw ex;
            } catch (MetaGraphApiException ex) {
                warnings.add(metric + ": " + concise(ex));
            }
        }
        return new InsightLoadResult(List.copyOf(result), List.copyOf(warnings));
    }

    public InsightLoadResult loadInstagramSummary(String objectId,
                                                   String accessToken,
                                                   Collection<String> metrics,
                                                   MetaApiModels.ReportPeriod period) {
        List<MetaApiModels.InsightMetric> loaded = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String metric : normalizeMetrics(metrics)) {
            MetaApiModels.InsightMetric value = null;
            String lastError = null;
            List<RequestVariant> variants = List.of(
                    new RequestVariant("day", "total_value"),
                    new RequestVariant(null, "total_value"),
                    new RequestVariant("day", null),
                    new RequestVariant(null, null)
            );
            for (RequestVariant variant : variants) {
                try {
                    InsightLoadResult attempt = load(
                            objectId,
                            accessToken,
                            List.of(metric),
                            period,
                            variant.period(),
                            variant.metricType(),
                            null,
                            null,
                            "Instagram"
                    );
                    if (!attempt.metrics().isEmpty()) {
                        value = attempt.metrics().getFirst();
                        break;
                    }
                    if (!attempt.warnings().isEmpty()) {
                        lastError = attempt.warnings().getLast();
                    }
                } catch (MetaTokenExpiredException ex) {
                    throw ex;
                }
            }
            if (value != null) {
                loaded.add(value);
            } else {
                warnings.add(lastError == null ? metric + ": metrica non restituita da Meta" : lastError);
            }
        }
        return new InsightLoadResult(List.copyOf(loaded), List.copyOf(warnings));
    }

    public Map<String, JsonNode> latestValues(List<MetaApiModels.InsightMetric> metrics) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (MetaApiModels.InsightMetric metric : metrics) {
            JsonNode total = metric.totalValue();
            if (total != null && !total.isNull() && !total.isMissingNode()) {
                result.put(metric.name(), total);
            } else if (metric.values() != null && !metric.values().isEmpty()) {
                result.put(metric.name(), metric.values().getLast().value());
            }
        }
        return Map.copyOf(result);
    }

    public Map<String, JsonNode> periodTotals(List<MetaApiModels.InsightMetric> metrics) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (MetaApiModels.InsightMetric metric : metrics) {
            JsonNode total = metric.totalValue();
            if (total != null && !total.isNull() && !total.isMissingNode()) {
                result.put(metric.name(), total);
                continue;
            }
            if (metric.values() == null || metric.values().isEmpty()) {
                continue;
            }
            boolean integral = true;
            double sum = 0;
            boolean numeric = true;
            for (MetaApiModels.InsightValue value : metric.values()) {
                if (value.value() == null || !value.value().isNumber()) {
                    numeric = false;
                    break;
                }
                integral &= value.value().isIntegralNumber();
                sum += value.value().asDouble();
            }
            if (numeric) {
                result.put(metric.name(), integral ? LongNode.valueOf((long) sum) : DoubleNode.valueOf(sum));
            }
        }
        return Map.copyOf(result);
    }

    public List<MetaApiModels.TrendPoint> toTrend(List<MetaApiModels.InsightMetric> metrics) {
        Map<String, Map<String, JsonNode>> points = new TreeMap<>();
        for (MetaApiModels.InsightMetric metric : metrics) {
            if (metric.values() == null) {
                continue;
            }
            for (MetaApiModels.InsightValue value : metric.values()) {
                String key = normalizePeriodKey(value.endTime());
                points.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .put(metric.name(), value.value());
            }
        }
        return points.entrySet().stream()
                .map(entry -> new MetaApiModels.TrendPoint(entry.getKey(), Map.copyOf(entry.getValue())))
                .toList();
    }

    public List<String> parseMetrics(String metrics, List<String> defaults) {
        if (!StringUtils.hasText(metrics)) {
            return defaults;
        }
        return normalizeMetrics(List.of(metrics.split(",")));
    }

    private List<String> normalizeMetrics(Collection<String> metrics) {
        Set<String> result = new LinkedHashSet<>();
        for (String metric : metrics) {
            if (metric == null) {
                continue;
            }
            String normalized = metric.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (!METRIC_NAME.matcher(normalized).matches()) {
                throw new IllegalArgumentException("Nome metrica Meta non valido: " + normalized);
            }
            result.add(normalized);
            if (result.size() > MAX_METRICS) {
                throw new IllegalArgumentException("Puoi richiedere al massimo " + MAX_METRICS + " metriche per chiamata");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Specificare almeno una metrica Meta");
        }
        return List.copyOf(result);
    }

    private MetaApiModels.InsightMetric toMetric(JsonNode item) {
        List<MetaApiModels.InsightValue> values = new ArrayList<>();
        item.path("values").forEach(value -> values.add(new MetaApiModels.InsightValue(
                value.path("end_time").asText(null),
                value.path("value")
        )));
        JsonNode totalValue = item.path("total_value").path("value");
        if (totalValue.isMissingNode()) {
            totalValue = null;
        }
        return new MetaApiModels.InsightMetric(
                item.path("name").asText(null),
                item.path("period").asText(null),
                item.path("title").asText(null),
                item.path("description").asText(null),
                List.copyOf(values),
                totalValue
        );
    }

    private String normalizePeriodKey(String endTime) {
        if (!StringUtils.hasText(endTime)) {
            return "unknown";
        }
        try {
            return LocalDate.parse(endTime.substring(0, 10)).minusDays(1).toString();
        } catch (Exception ignored) {
            return endTime;
        }
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String concise(MetaGraphApiException ex) {
        StringBuilder message = new StringBuilder(ex.getMessage());
        if (ex.getGraphCode() != null) {
            message.append(" [code=").append(ex.getGraphCode());
            if (ex.getGraphSubcode() != null) {
                message.append(", subcode=").append(ex.getGraphSubcode());
            }
            message.append(']');
        }
        return message.toString();
    }

    private record RequestVariant(String period, String metricType) {
    }

    public record InsightLoadResult(
            List<MetaApiModels.InsightMetric> metrics,
            List<String> warnings
    ) {
    }
}
