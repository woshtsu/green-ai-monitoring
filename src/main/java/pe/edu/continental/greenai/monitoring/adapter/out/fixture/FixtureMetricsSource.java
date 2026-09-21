package pe.edu.continental.greenai.monitoring.adapter.out.fixture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MetricData.*;

/** Small synthetic test data; this adapter is not the future Simulator Service. */
public final class FixtureMetricsSource implements MetricsSource {
    @Override public SourceResult query(MetricQuery query) {
        List<RawSeries> result = new ArrayList<>();
        for (int node = 1; node <= 2; node++) {
            Resource resource = new Resource("node", "fixture-lab", "fixture-node-0" + node);
            if (!query.matches(resource.type(), resource.cluster(), resource.id())) continue;
            for (Map<String, String> labels : labels(query.metric().id())) {
                List<RawSample> samples = new ArrayList<>();
                for (int i = 0; i < query.pointsPerSeries(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted fixture");
                    Instant timestamp = query.timestampAt(i);
                    int phase = (int) Math.floorMod(Math.floorDiv(timestamp.getEpochSecond(), 15), 4);
                    if (node == 1 && phase == 1) continue;
                    double value = node == 1 && phase == 0 ? 0
                        : node == 1 && phase == 2 ? Double.NaN : base(query.metric().id()) * node;
                    samples.add(new RawSample(timestamp, value));
                }
                if (!samples.isEmpty()) {
                    result.add(new RawSeries(resource, labels, Source.fixture, Origin.simulated, samples));
                }
            }
        }
        return new SourceResult(result, List.of());
    }

    private List<Map<String, String>> labels(String metric) {
        if (metric.startsWith("node.network.")) {
            return List.of(Map.of("device", "eth0"), Map.of("device", "eth1"));
        }
        if (metric.equals("node.filesystem.used")) {
            return List.of(Map.of("device", "/dev/fixture-a", "mountpoint", "/", "fstype", "ext4"),
                Map.of("device", "/dev/fixture-b", "mountpoint", "/data", "fstype", "xfs"));
        }
        return List.of(Map.of());
    }

    private double base(String metric) {
        return switch (metric) {
            case "node.cpu.utilization" -> 0.25;
            case "node.memory.used" -> 1_073_741_824d;
            case "node.filesystem.used" -> 10_737_418_240d;
            default -> 1024d;
        };
    }
}
