package pe.edu.continental.greenai.monitoring.domain;

import java.util.List;

public final class MetricCatalog {
    private MetricCatalog() { }

    private static final List<MetricDefinition> METRICS = List.of(
        new MetricDefinition("node.cpu.utilization", "A", "node", "ratio", "mean_non_idle", 60,
            "1 - media de la tasa idle por CPU lógica en 60 s. Iowait cuenta como no-idle.",
            "Capacidad de todas las CPU lógicas del nodo; no requests/limits de pods.", List.of()),
        new MetricDefinition("node.memory.used", "A", "node", "bytes", "instant", null,
            "MemTotal - MemAvailable; no equivale a MemTotal - MemFree.",
            "No aplica: magnitud absoluta.", List.of()),
        new MetricDefinition("node.network.receive", "A", "node", "bytes/s", "rate", 60,
            "Tasa de bytes recibidos en 60 s por interfaz, sin sumar interfaces.",
            "Segundos transcurridos en la ventana de 60 s.", List.of("device")),
        new MetricDefinition("node.network.transmit", "A", "node", "bytes/s", "rate", 60,
            "Tasa de bytes transmitidos en 60 s por interfaz, sin sumar interfaces.",
            "Segundos transcurridos en la ventana de 60 s.", List.of("device")),
        new MetricDefinition("node.filesystem.used", "A", "node", "bytes", "instant", null,
            "size - free por filesystem (incluye bloques reservados en free); no size - avail. "
                + "Solo ext2/ext3/ext4/xfs/btrfs/zfs; excluir /proc,/sys,/dev,/run y descendientes.",
            "No aplica: bytes absolutos; capacidad de referencia size del mismo filesystem.",
            List.of("device", "mountpoint", "fstype"))
    );

    public static List<MetricDefinition> all() { return METRICS; }

    public static MetricDefinition require(String id) {
        return METRICS.stream().filter(metric -> metric.id().equals(id)).findFirst()
            .orElseThrow(() -> MonitoringFailure.invalid("Métrica no permitida."));
    }
}
