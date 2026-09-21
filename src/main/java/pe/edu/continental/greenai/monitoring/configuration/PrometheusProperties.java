package pe.edu.continental.greenai.monitoring.configuration;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.edu.continental.greenai.monitoring.domain.MetricData.Origin;

@ConfigurationProperties("monitoring.prometheus")
public record PrometheusProperties(URI url,
    @DefaultValue("cluster") String clusterLabel,
    @DefaultValue("lab-01") String defaultCluster,
    @DefaultValue("instance") String instanceLabel,
    @DefaultValue("origin") String originLabel,
    @DefaultValue("unknown") Origin defaultOrigin) {

    public PrometheusProperties {
        if (url != null && (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
            || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
            || url.getPort() == 0 || url.getPort() > 65535)) {
            throw new IllegalArgumentException("monitoring.prometheus.url debe ser HTTP(S), sin credenciales, query o fragmento.");
        }
        for (String label : new String[]{clusterLabel, instanceLabel, originLabel}) {
            if (label == null || !label.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || label.startsWith("__")
                || Set.of("device", "mountpoint", "fstype", "cpu", "mode").contains(label)) {
                throw new IllegalArgumentException("Etiqueta Prometheus de identidad inválida o reservada.");
            }
        }
        if (new HashSet<>(List.of(clusterLabel, instanceLabel, originLabel)).size() != 3
            || defaultCluster == null || !defaultCluster.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
            || defaultOrigin == null) {
            throw new IllegalArgumentException("Etiquetas distintas, default-cluster y default-origin válidos son obligatorios.");
        }
    }
}
