package pe.edu.continental.greenai.monitoring.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pe.edu.continental.greenai.monitoring.application.port.in.QueryMetrics;
import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.*;

public final class MonitoringService implements QueryMetrics {
    private final MetricsSource source;
    private final Clock clock;

    public MonitoringService(MetricsSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override public List<MetricDefinition> catalog() { return MetricCatalog.all(); }

    @Override public Response current(String metric, String resourceType, String cluster, String resourceId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return execute(new MetricQuery(MetricCatalog.require(metric), resourceType, cluster, resourceId, now, now, null));
    }

    @Override public Response history(String metric, String resourceType, String cluster, String resourceId,
                                      Instant start, Instant end, int stepSeconds) {
        if (start == null || end == null || end.isAfter(clock.instant())) {
            throw MonitoringFailure.invalid("Fechas requeridas; end no puede estar en el futuro.");
        }
        return execute(new MetricQuery(MetricCatalog.require(metric), resourceType, cluster, resourceId,
            start, end, stepSeconds));
    }

    private Response execute(MetricQuery query) {
        SourceResult raw = source.query(query);
        if (raw == null) throw invalidSource();
        if (raw.series().size() > MetricQuery.MAX_SERIES
            || (long) raw.series().size() * query.pointsPerSeries() > MetricQuery.MAX_POINTS) {
            throw MonitoringFailure.limit("La consulta supera el máximo de series o puntos.");
        }
        List<Series> series = new ArrayList<>();
        // Do not expose arbitrary upstream warning text (may contain queries or internal URLs).
        List<String> warnings = new ArrayList<>();
        if (!raw.warnings().isEmpty()) warnings.add("SOURCE_WARNING");
        boolean partial = !warnings.isEmpty();
        Set<Identity> identities = new HashSet<>();
        for (RawSeries item : raw.series()) {
            if (item.samples().isEmpty()) continue;
            if (item.resource() == null || item.source() == null || item.origin() == null
                || !query.matches(item.resource().type(), item.resource().cluster(), item.resource().id())
                || !item.labels().keySet().equals(Set.copyOf(query.metric().seriesLabels()))
                || !identities.add(new Identity(item.resource(), item.labels()))
                || item.samples().size() > query.pointsPerSeries()) {
                throw invalidSource();
            }
            Map<Instant, Double> values = new HashMap<>();
            for (RawSample sample : item.samples()) {
                Instant timestamp = sample.timestamp();
                if (timestamp == null || timestamp.getNano() != 0 || timestamp.isBefore(query.start())
                    || timestamp.isAfter(query.end())
                    || (query.stepSeconds() != null
                        && (timestamp.getEpochSecond() - query.start().getEpochSecond()) % query.stepSeconds() != 0)
                    || values.putIfAbsent(timestamp, sample.value()) != null) {
                    throw invalidSource();
                }
            }
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < query.pointsPerSeries(); i++) {
                Instant timestamp = query.timestampAt(i);
                Double value = values.get(timestamp);
                Quality quality = value == null ? Quality.missing
                    : Double.isFinite(value) ? Quality.valid : Quality.non_finite;
                if (quality != Quality.valid) partial = true;
                if (quality == Quality.non_finite && !warnings.contains("NON_FINITE_VALUE")) {
                    warnings.add("NON_FINITE_VALUE");
                }
                samples.add(new Sample(timestamp, quality == Quality.valid ? value : null, quality));
            }
            series.add(new Series(item.resource(), item.labels(), item.source(), item.origin(), samples));
        }
        Status status = series.isEmpty() ? Status.no_data : partial ? Status.partial : Status.complete;
        return new Response(query.metric().id(), query.metric().unit(), query.metric().aggregation(),
            query.metric().windowSeconds(), query.start(), query.end(), query.stepSeconds(), status, series, warnings);
    }

    private static MonitoringFailure invalidSource() {
        return new MonitoringFailure(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE,
            "La fuente devolvió series inválidas.");
    }

    private record Identity(Resource resource, Map<String, String> labels) { }
}
