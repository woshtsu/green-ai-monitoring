package pe.edu.continental.greenai.monitoring.application.port.in;

import java.time.Instant;
import java.util.List;
import pe.edu.continental.greenai.monitoring.domain.MetricDefinition;
import pe.edu.continental.greenai.monitoring.domain.MetricData.Response;

public interface QueryMetrics {
    List<MetricDefinition> catalog();
    Response current(String metric, String resourceType, String cluster, String resourceId);
    Response history(String metric, String resourceType, String cluster, String resourceId,
                     Instant start, Instant end, int stepSeconds);
}
