package pe.edu.continental.greenai.monitoring.application.port.out;

import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MetricData.SourceResult;

/** Adapters must return evaluation timestamps on the requested grid, preserving identity and origin. */
@FunctionalInterface
public interface MetricsSource {
    SourceResult query(MetricQuery query);
}
