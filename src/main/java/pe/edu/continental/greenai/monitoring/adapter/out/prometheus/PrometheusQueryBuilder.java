package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import java.util.ArrayList;
import java.util.List;
import pe.edu.continental.greenai.monitoring.configuration.PrometheusProperties;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

public final class PrometheusQueryBuilder {
    public static final String FILESYSTEM_TYPES = "ext2|ext3|ext4|xfs|btrfs|zfs";
    public static final String EXCLUDED_MOUNTS = "/(proc|sys|dev|run)(/.*)?";
    private final PrometheusProperties properties;

    public PrometheusQueryBuilder(PrometheusProperties properties) { this.properties = properties; }

    public String build(MetricQuery query) {
        List<String> filters = new ArrayList<>();
        if (query.cluster() != null) {
            // Empty label matcher also matches an absent label, which maps to defaultCluster.
            filters.add(query.cluster().equals(properties.defaultCluster())
                ? properties.clusterLabel() + "=~" + quote(query.cluster().replace(".", "\\.") + "|")
                : properties.clusterLabel() + "=" + quote(query.cluster()));
        }
        filters.add(properties.instanceLabel() + (query.resourceId() == null ? "!=\"\"" : "=" + quote(query.resourceId())));
        String selector = selector(filters);
        String identity = properties.clusterLabel() + "," + properties.instanceLabel() + "," + properties.originLabel();
        return switch (query.metric().id()) {
            case "node.cpu.utilization" -> {
                filters.add("mode=\"idle\"");
                yield "1 - avg by (" + identity + ") (rate(node_cpu_seconds_total" + selector(filters) + "[1m]))";
            }
            case "node.memory.used" -> "node_memory_MemTotal_bytes" + selector + " - node_memory_MemAvailable_bytes" + selector;
            case "node.network.receive" -> "rate(node_network_receive_bytes_total" + selector + "[1m])";
            case "node.network.transmit" -> "rate(node_network_transmit_bytes_total" + selector + "[1m])";
            case "node.filesystem.used" -> {
                filters.add("fstype=~" + quote(FILESYSTEM_TYPES));
                filters.add("mountpoint!~" + quote(EXCLUDED_MOUNTS));
                String filesystem = selector(filters);
                yield "(node_filesystem_size_bytes" + filesystem + " - node_filesystem_free_bytes" + filesystem
                    + ") and on (" + identity + ",device,mountpoint,fstype) (node_filesystem_device_error" + filesystem + " == 0)";
            }
            default -> throw MonitoringFailure.invalid("Métrica no permitida.");
        };
    }

    private String selector(List<String> filters) { return "{" + String.join(",", filters) + "}"; }
    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
