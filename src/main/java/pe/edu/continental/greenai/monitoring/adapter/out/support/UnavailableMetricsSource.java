package pe.edu.continental.greenai.monitoring.adapter.out.support;

import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import pe.edu.continental.greenai.monitoring.domain.MetricData.SourceResult;

public final class UnavailableMetricsSource implements MetricsSource {
    @Override public SourceResult query(MetricQuery query) {
        throw new MonitoringFailure(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE,
            "No hay una fuente de métricas configurada.");
    }
}
