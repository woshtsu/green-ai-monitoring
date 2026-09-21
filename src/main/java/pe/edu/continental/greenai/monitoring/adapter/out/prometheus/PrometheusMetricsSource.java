package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MetricData.SourceResult;

public final class PrometheusMetricsSource implements MetricsSource, AutoCloseable {
    private final PrometheusClient client;
    private final PrometheusQueryBuilder builder;
    private final PrometheusResponseMapper mapper;

    public PrometheusMetricsSource(PrometheusClient client, PrometheusQueryBuilder builder, PrometheusResponseMapper mapper) {
        this.client = client;
        this.builder = builder;
        this.mapper = mapper;
    }

    @Override public SourceResult query(MetricQuery query) { return mapper.map(client.query(query, builder.build(query)), query); }
    @Override public void close() { client.close(); }
}
