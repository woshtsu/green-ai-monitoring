package pe.edu.continental.greenai.monitoring.configuration;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.edu.continental.greenai.monitoring.application.port.in.QueryMetrics;
import pe.edu.continental.greenai.monitoring.adapter.out.support.BoundedMetricsSource;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import pe.edu.continental.greenai.monitoring.domain.MetricData.Origin;
import tools.jackson.databind.json.JsonMapper;

class MonitoringConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .withUserConfiguration(MonitoringConfiguration.class);

    @Test void noUrlKeepsUnavailableSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BoundedMetricsSource.class);
            assertThat(context.getBean(PrometheusProperties.class).defaultCluster()).isEqualTo("lab-01");
            assertThatThrownBy(() -> context.getBean(QueryMetrics.class).current("node.memory.used", "node", null, null))
                .isInstanceOfSatisfying(MonitoringFailure.class, error -> assertThat(error.code()).isEqualTo(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE));
        });
    }

    @Test void fixtureWinsEvenWithAnUnreachableConfiguredUrl() {
        runner.withPropertyValues("spring.profiles.active=fixture", "monitoring.prometheus.url=http://127.0.0.1:1")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(BoundedMetricsSource.class);
                assertThat(context.getBean(QueryMetrics.class).current("node.memory.used", "node", null, "fixture-node-02")
                    .series().getFirst().origin()).isEqualTo(Origin.simulated);
            });
    }

    @Test void invalidUrlFailsStartupRatherThanSilentlyUsingFixture() {
        runner.withPropertyValues("monitoring.prometheus.url=file:///tmp/not-prometheus")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test void operatorCanOverrideDefaultCluster() {
        runner.withPropertyValues("monitoring.prometheus.default-cluster=university-lab")
            .run(context -> assertThat(context.getBean(PrometheusProperties.class).defaultCluster()).isEqualTo("university-lab"));
    }
}
