package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.edu.continental.greenai.monitoring.configuration.PrometheusProperties;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.Origin;

class PrometheusQueryBuilderTest {
    private final PrometheusQueryBuilder builder = new PrometheusQueryBuilder(PrometheusTestData.properties(null));

    @Test void cpuKeepsClusterAndOriginInGrouping() {
        assertThat(builder.build(PrometheusTestData.query("node.cpu.utilization", false)))
            .isEqualTo("1 - avg by (cluster,instance,origin) (rate(node_cpu_seconds_total{instance!=\"\",mode=\"idle\"}[1m]))");
    }

    @Test void buildsEveryMetricWithoutDroppingFilesystemDimensions() {
        assertThat(builder.build(PrometheusTestData.query("node.memory.used", false)))
            .isEqualTo("node_memory_MemTotal_bytes{instance!=\"\"} - node_memory_MemAvailable_bytes{instance!=\"\"}");
        assertThat(builder.build(PrometheusTestData.query("node.network.receive", false)))
            .isEqualTo("rate(node_network_receive_bytes_total{instance!=\"\"}[1m])");
        assertThat(builder.build(PrometheusTestData.query("node.network.transmit", false)))
            .isEqualTo("rate(node_network_transmit_bytes_total{instance!=\"\"}[1m])");
        assertThat(builder.build(PrometheusTestData.query("node.filesystem.used", false)))
            .contains("node_filesystem_free_bytes", "fstype=~\"ext2|ext3|ext4|xfs|btrfs|zfs\"",
                "mountpoint!~\"/(proc|sys|dev|run)(/.*)?\"", "and on (cluster,instance,origin,device,mountpoint,fstype)",
                "node_filesystem_device_error").doesNotContain("avail_bytes");
    }

    @Test void defaultClusterMatchesMissingLabelsAndInstanceUsesExactPort() {
        MetricQuery query = new MetricQuery(MetricCatalog.require("node.memory.used"), "node", "lab-01", "host:9100",
            Instant.EPOCH, Instant.EPOCH, null);
        assertThat(builder.build(query)).contains("cluster=~\"lab-01|\"", "instance=\"host:9100\"");
        MetricQuery other = new MetricQuery(query.metric(), "node", "lab-02", "host:9200", Instant.EPOCH, Instant.EPOCH, null);
        assertThat(builder.build(other)).contains("cluster=\"lab-02\"", "instance=\"host:9200\"").doesNotContain("cluster=~");
    }

    @Test void escapesDotsInDefaultClusterRegexAndSupportsStableNodeLabel() {
        var properties = new PrometheusProperties(null, "site", "lab.01", "node", "provenance", Origin.unknown);
        var query = new MetricQuery(MetricCatalog.require("node.cpu.utilization"), "node", "lab.01", "node-1", Instant.EPOCH, Instant.EPOCH, null);
        assertThat(new PrometheusQueryBuilder(properties).build(query)).contains("site=~\"lab\\\\.01|\"", "node=\"node-1\"", "avg by (site,node,provenance)");
    }

    @ParameterizedTest @ValueSource(strings = {"/proc", "/proc/1", "/sys", "/dev/shm", "/run/user/1"})
    void excludesOnlyVirtualMountTrees(String mount) { assertThat(mount.matches(PrometheusQueryBuilder.EXCLUDED_MOUNTS)).isTrue(); }

    @ParameterizedTest @ValueSource(strings = {"/", "/data", "/device-data", "/runtime", "/system"})
    void preservesSimilarlyNamedRealMounts(String mount) { assertThat(mount.matches(PrometheusQueryBuilder.EXCLUDED_MOUNTS)).isFalse(); }

    @Test void rejectsUntrustedSelectorAndInvalidConfiguration() {
        assertThatThrownBy(() -> new MetricQuery(MetricCatalog.require("node.memory.used"), "node", "x\"} or up", null,
            Instant.EPOCH, Instant.EPOCH, null)).isInstanceOf(MonitoringFailure.class);
        assertThatThrownBy(() -> new PrometheusProperties(null, "cluster\"", "lab", "instance", "origin", Origin.unknown))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrometheusProperties(null, "cluster", "lab", "cluster", "origin", Origin.unknown))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrometheusTestData.properties(URI.create("http://user:secret@localhost:9090")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
