package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import pe.edu.continental.greenai.monitoring.configuration.PrometheusProperties;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import pe.edu.continental.greenai.monitoring.domain.MetricData.*;
import static pe.edu.continental.greenai.monitoring.domain.MonitoringFailure.Code.*;

public final class PrometheusResponseMapper {
    private final PrometheusProperties properties;
    public PrometheusResponseMapper(PrometheusProperties properties) { this.properties = properties; }

    public SourceResult map(JsonNode envelope, MetricQuery query) {
        try {
            JsonNode data = envelope.required("data");
            String expected = query.stepSeconds() == null ? "vector" : "matrix";
            if (!expected.equals(data.required("resultType").asString()) || !data.required("result").isArray()) throw invalid();
            JsonNode result = data.get("result");
            if (result.size() > MetricQuery.MAX_SERIES || (long) result.size() * query.pointsPerSeries() > MetricQuery.MAX_POINTS) {
                throw PrometheusClient.failure(QUERY_LIMIT_EXCEEDED);
            }
            List<String> warnings = new ArrayList<>();
            for (String field : List.of("warnings", "infos")) {
                if (envelope.has(field)) {
                    if (!envelope.get(field).isArray()) throw invalid();
                    for (JsonNode warning : envelope.get(field)) if (!warning.isString()) throw invalid();
                    if (!envelope.get(field).isEmpty()) warnings.add("PROMETHEUS_" + field.toUpperCase(Locale.ROOT));
                }
            }
            List<RawSeries> series = new ArrayList<>();
            for (JsonNode item : result) {
                JsonNode metric = item.required("metric");
                if (!metric.isObject()) throw invalid();
                String cluster = optionalLabel(metric, properties.clusterLabel(), properties.defaultCluster());
                String instance = label(metric, properties.instanceLabel());
                if (!cluster.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    || !instance.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw invalid();
                Origin origin = Origin.valueOf(optionalLabel(metric, properties.originLabel(), properties.defaultOrigin().name()));
                Map<String, String> labels = new LinkedHashMap<>();
                for (String name : query.metric().seriesLabels()) labels.put(name, label(metric, name));
                if (query.metric().id().equals("node.filesystem.used")
                    && (!labels.get("fstype").matches(PrometheusQueryBuilder.FILESYSTEM_TYPES)
                        || labels.get("mountpoint").matches(PrometheusQueryBuilder.EXCLUDED_MOUNTS))) throw invalid();
                List<RawSample> samples = new ArrayList<>();
                if (query.stepSeconds() == null) {
                    samples.add(sample(item.required("value")));
                } else {
                    JsonNode values = item.required("values");
                    if (!values.isArray()) throw invalid();
                    if (values.size() > query.pointsPerSeries()) throw invalid();
                    for (JsonNode value : values) samples.add(sample(value));
                }
                series.add(new RawSeries(new Resource("node", cluster, instance), labels, Source.prometheus, origin, samples));
            }
            return new SourceResult(series, warnings);
        } catch (MonitoringFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private RawSample sample(JsonNode pair) {
        if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isNumber() || !pair.get(1).isString()) throw invalid();
        Instant timestamp = Instant.ofEpochSecond(new BigDecimal(pair.get(0).toString()).longValueExact());
        String text = pair.get(1).asString();
        double value = switch (text) {
            case "NaN" -> Double.NaN;
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw invalid();
                yield Double.parseDouble(text);
            }
        };
        return new RawSample(timestamp, value);
    }

    private String label(JsonNode metric, String name) {
        JsonNode value = metric.required(name);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 1024) throw invalid();
        return value.asString();
    }

    private String optionalLabel(JsonNode metric, String name, String fallback) {
        if (!metric.has(name)) return fallback;
        JsonNode value = metric.get(name);
        if (!value.isString()) throw invalid();
        return value.asString().isEmpty() ? fallback : label(metric, name);
    }

    private MonitoringFailure invalid() { return PrometheusClient.failure(UPSTREAM_INVALID_RESPONSE); }
}
