package pe.edu.continental.greenai.monitoring.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record MetricQuery(MetricDefinition metric, String resourceType, String cluster,
                          String resourceId, Instant start, Instant end, Integer stepSeconds) {
    public static final int MAX_SERIES = 100;
    public static final int MAX_POINTS = 10_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    public MetricQuery {
        Objects.requireNonNull(metric);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (!metric.resourceType().equals(resourceType)) {
            throw MonitoringFailure.invalid("resourceType no compatible con la métrica.");
        }
        validateFilter(cluster);
        validateFilter(resourceId);
        if (start.getNano() != 0 || end.getNano() != 0) {
            throw MonitoringFailure.invalid("Se requieren timestamps con segundos enteros.");
        }
        if (stepSeconds == null) {
            if (!start.equals(end)) throw MonitoringFailure.invalid("Consulta actual inválida.");
        } else {
            if (stepSeconds < 15 || stepSeconds > 3600 || !start.isBefore(end)) {
                throw MonitoringFailure.invalid("Intervalo o resolución inválidos.");
            }
            if (Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0) {
                throw MonitoringFailure.limit("El rango máximo es de 24 horas.");
            }
        }
    }

    private static void validateFilter(String value) {
        if (value != null && !ID.matcher(value).matches()) {
            throw MonitoringFailure.invalid("Filtro de recurso inválido.");
        }
    }

    public int pointsPerSeries() {
        return stepSeconds == null ? 1 : (int) (Duration.between(start, end).getSeconds() / stepSeconds + 1);
    }

    public Instant timestampAt(int index) {
        return start.plusSeconds(stepSeconds == null ? 0 : (long) index * stepSeconds);
    }

    public boolean matches(String type, String clusterId, String id) {
        return resourceType.equals(type) && (cluster == null || cluster.equals(clusterId))
            && (resourceId == null || resourceId.equals(id));
    }
}
