package pe.edu.continental.greenai.monitoring.domain;

import java.util.List;

public record MetricDefinition(String id, String group, String resourceType, String unit,
                               String aggregation, Integer windowSeconds, String description,
                               String denominator, List<String> seriesLabels) {
    public MetricDefinition { seriesLabels = List.copyOf(seriesLabels); }
}
