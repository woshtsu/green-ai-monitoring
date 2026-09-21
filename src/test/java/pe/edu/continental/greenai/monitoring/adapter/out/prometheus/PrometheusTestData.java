package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import java.net.URI;
import java.time.Instant;
import pe.edu.continental.greenai.monitoring.configuration.PrometheusProperties;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.Origin;

final class PrometheusTestData {
    static PrometheusProperties properties(URI url) {
        return new PrometheusProperties(url, "cluster", "lab-01", "instance", "origin", Origin.unknown);
    }
    static MetricQuery query(String metric, boolean history) {
        return new MetricQuery(MetricCatalog.require(metric), "node", null, null, Instant.ofEpochSecond(120),
            Instant.ofEpochSecond(history ? 150 : 120), history ? 15 : null);
    }
    static String vector(String labels, String value) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{" + labels
            + "},\"value\":[120,\"" + value + "\"]}]}}";
    }
}
