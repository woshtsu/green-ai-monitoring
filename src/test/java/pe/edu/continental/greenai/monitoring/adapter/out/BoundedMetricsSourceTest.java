package pe.edu.continental.greenai.monitoring.adapter.out;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import pe.edu.continental.greenai.monitoring.adapter.out.support.*;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.SourceResult;

class BoundedMetricsSourceTest {
    private final MetricQuery query = new MetricQuery(MetricCatalog.require("node.memory.used"), "node", null, null,
        Instant.EPOCH, Instant.EPOCH, null);

    @Test void timesOutAndRejectsMoreWorkWhileNonCooperativeTaskIsStillRunning() {
        CountDownLatch release = new CountDownLatch(1);
        try (BoundedMetricsSource source = new BoundedMetricsSource(q -> {
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* deliberately non-cooperative */ }
            }
            return new SourceResult(List.of(), List.of());
        }, Duration.ofMillis(100), 1)) {
            try {
                assertThatThrownBy(() -> source.query(query)).isInstanceOfSatisfying(MonitoringFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.METRICS_SOURCE_TIMEOUT));
                assertThatThrownBy(() -> source.query(query)).isInstanceOfSatisfying(MonitoringFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE));
            } finally { release.countDown(); }
        }
    }

    @Test void propagatesContractFailuresAndHidesUnexpectedSourceMessages() {
        try (BoundedMetricsSource source = new BoundedMetricsSource(new UnavailableMetricsSource(), Duration.ofSeconds(1), 1)) {
            assertThatThrownBy(() -> source.query(query)).isInstanceOfSatisfying(MonitoringFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE));
        }
        try (BoundedMetricsSource source = new BoundedMetricsSource(q -> { throw new RuntimeException("private URL"); }, Duration.ofSeconds(1), 1)) {
            assertThatThrownBy(() -> source.query(query)).isInstanceOfSatisfying(MonitoringFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE);
                assertThat(failure.getMessage()).doesNotContain("private");
            });
        }
    }
}
