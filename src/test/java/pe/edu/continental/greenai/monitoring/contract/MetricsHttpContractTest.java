package pe.edu.continental.greenai.monitoring.contract;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import pe.edu.continental.greenai.monitoring.domain.MetricCatalog;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("fixture")
@AutoConfigureMetrics
class MetricsHttpContractTest {
    private static final String ROOT = "/api/v1/metrics";
    @LocalServerPort int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-09-22T15:00:00Z"), ZoneOffset.UTC); }
    }

    @Test void catalogDocumentsAllFiveMetricsAndFilesystemLabels() throws Exception {
        var response = OpenApiAssertions.validate(ROOT + "/catalog", get(ROOT + "/catalog"), 200);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.get(4).get("seriesLabels").toString()).isEqualTo("[\"device\",\"mountpoint\",\"fstype\"]");
        assertThat(response.get(0).get("denominator").asString()).contains("CPU lógicas");
    }

    @ParameterizedTest @ValueSource(strings = {"node.cpu.utilization", "node.memory.used", "node.network.receive", "node.network.transmit", "node.filesystem.used"})
    void currentAndHistoryConformForEveryMetric(String metric) throws Exception {
        var current = OpenApiAssertions.validate(ROOT + "/current", get(ROOT + "/current?metric=" + metric), 200);
        assertThat(current.get("start").asString()).isEqualTo("2026-09-22T15:00:00Z");
        assertThat(current.get("stepSeconds").isNull()).isTrue();
        assertThat(current.get("dataStatus").asString()).isEqualTo("complete");
        var history = OpenApiAssertions.validate(ROOT + "/history", get(history(metric)), 200);
        assertThat(history.get("dataStatus").asString()).isEqualTo("partial");
        var samples = history.get("series").get(0).get("samples");
        assertThat(samples.get(0).get("value").asDouble()).isZero();
        assertThat(samples.get(1).get("quality").asString()).isEqualTo("missing");
        assertThat(samples.get(2).get("quality").asString()).isEqualTo("non_finite");
        assertThat(samples.get(2).get("value").isNull()).isTrue();
    }

    @Test void normalizesOffsetToUtcAndFiltersExactIdentity() throws Exception {
        String url = ROOT + "/history?metric=node.memory.used&resourceType=node&cluster=fixture-lab&resourceId=fixture-node-02"
            + "&start=2026-09-21T10:00:00-05:00&end=2026-09-21T10:00:31-05:00";
        var body = OpenApiAssertions.validate(ROOT + "/history", get(url), 200);
        assertThat(body.get("start").asString()).isEqualTo("2026-09-21T15:00:00Z");
        assertThat(body.get("series").size()).isEqualTo(1);
        assertThat(body.get("series").get(0).get("samples").size()).isEqualTo(3);
        assertThat(body.get("dataStatus").asString()).isEqualTo("complete");
    }

    @Test void unknownResourceIsNoDataNotZero() throws Exception {
        var body = OpenApiAssertions.validate(ROOT + "/current", get(ROOT + "/current?metric=node.memory.used&resourceId=unknown"), 200);
        assertThat(body.get("dataStatus").asString()).isEqualTo("no_data");
        assertThat(body.get("series").isEmpty()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {
        "", "?metric=", "?metric=up", "?metric=node.memory.used&metric=node.memory.used",
        "?metric=node.memory.used&query=up", "?metric=node.memory.used&stepSeconds=15",
        "?metric=node.memory.used&resourceType=pod", "?metric=node.memory.used&cluster=",
        "?metric=node.memory.used&origin=observed", "?metric=node.memory.used&resourceId=a%22%7D"
    })
    void rejectsInvalidCurrentParameters(String query) throws Exception {
        var body = OpenApiAssertions.validate(ROOT + "/current", get(ROOT + "/current" + query), 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_QUERY");
    }

    @ParameterizedTest @ValueSource(strings = {
        "&stepSeconds=14", "&stepSeconds=3601", "&stepSeconds=1.5", "&stepSeconds=9999999999999",
        "&start=2026-09-21T15:00:00Z", "&promql=up"
    })
    void rejectsInvalidHistoryParameters(String extra) throws Exception {
        OpenApiAssertions.validate(ROOT + "/history", get(history("node.memory.used") + extra), 400);
    }

    @ParameterizedTest @ValueSource(strings = {"2026-09-21T15:00:00", "2026-09-21T15:00:00.1Z", "2026-02-30T15:00:00Z", "2026-09-21T15:00:00-00:00", "2026-09-23T15:00:00Z"})
    void rejectsInvalidOrFutureTimestamps(String end) throws Exception {
        OpenApiAssertions.validate(ROOT + "/history", get(ROOT + "/history?metric=node.memory.used&start=2026-09-21T15:00:00Z&end="
            + URLEncoder.encode(end, StandardCharsets.UTF_8)), 400);
    }

    @Test void limitsRangeAndTotalPointsWithProblemJson() throws Exception {
        for (String start : new String[]{"2026-09-20T15:00:00Z", "2026-09-21T15:00:00Z"}) {
            var body = OpenApiAssertions.validate(ROOT + "/history", get(ROOT + "/history?metric=node.memory.used&start="
                + start + "&end=2026-09-22T15:00:00Z"), 422);
            assertThat(body.get("code").asString()).isEqualTo("QUERY_LIMIT_EXCEEDED");
        }
    }

    @Test void servesStaticOpenApiWithoutGatewayPrefixOrExtraCatalogParameters() throws Exception {
        var spec = get("/openapi/monitoring-v0.1.json");
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("\"openapi\": \"3.1.0\"");
        assertThat(OpenApiAssertions.specification().get("paths").size()).isEqualTo(3);
        assertThat(get("/api/monitoring/v1/metrics/catalog").statusCode()).isEqualTo(404);
        OpenApiAssertions.validate(ROOT + "/catalog", get(ROOT + "/catalog?query=up"), 400);
        assertThat(MetricCatalog.all()).hasSize(5);
    }

    @Test void healthAndTechnicalScrapeAreSeparateAndSensitiveActuatorEndpointsStayHidden() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        HttpResponse<String> scrape = get("/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test void generatesFreshRequestIdsRatherThanReflectingInput() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ROOT + "/catalog"))
                .header("X-Request-Id", "untrusted-input").GET().build(), HttpResponse.BodyHandlers.ofString());
            OpenApiAssertions.validate(ROOT + "/catalog", response, 200);
            assertThat(response.headers().firstValue("X-Request-Id").orElseThrow()).isNotEqualTo("untrusted-input");
        }
    }

    private String history(String metric) {
        return ROOT + "/history?metric=" + metric + "&start=2026-09-21T15:00:00Z&end=2026-09-21T15:00:45Z";
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
