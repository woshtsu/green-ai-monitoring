package pe.edu.continental.greenai.monitoring.contract;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Monitoring HTTP server -> real JDK HTTP adapter -> simulated Prometheus HTTP server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusHttpContractTest {
    private static final HttpServer UPSTREAM = startUpstream();
    @LocalServerPort int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("monitoring.prometheus.url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("monitoring.prometheus.default-cluster", () -> "test-lab");
        registry.add("monitoring.prometheus.instance-label", () -> "node");
        registry.add("monitoring.prometheus.origin-label", () -> "provenance");
    }

    @AfterAll static void stopUpstream() { UPSTREAM.stop(0); }

    @Test void realAdapterPreservesSameNodeAcrossClustersAndProvenance() throws Exception {
        var body = OpenApiAssertions.validate("/api/v1/metrics/current", get("/api/v1/metrics/current?metric=node.memory.used"), 200);
        assertThat(body.get("series").size()).isEqualTo(2);
        assertThat(body.get("series").get(0).get("resource").get("cluster").asString()).isEqualTo("test-lab");
        assertThat(body.get("series").get(0).get("origin").asString()).isEqualTo("unknown");
        assertThat(body.get("series").get(1).get("resource").get("cluster").asString()).isEqualTo("other-lab");
        assertThat(body.get("series").get(1).get("origin").asString()).isEqualTo("simulated");
        assertThat(body.get("series").get(0).get("source").asString()).isEqualTo("prometheus");
    }

    @Test void realAdapterHistoryRetainsGapAndNonFiniteWithoutZeroImputation() throws Exception {
        var body = OpenApiAssertions.validate("/api/v1/metrics/history",
            get("/api/v1/metrics/history?metric=node.memory.used&start=1970-01-01T00:02:00Z&end=1970-01-01T00:02:30Z"), 200);
        assertThat(body.get("dataStatus").asString()).isEqualTo("partial");
        var samples = body.get("series").get(0).get("samples");
        assertThat(samples.get(0).get("value").asDouble()).isZero();
        assertThat(samples.get(1).get("quality").asString()).isEqualTo("missing");
        assertThat(samples.get(2).get("quality").asString()).isEqualTo("non_finite");
    }

    @Test void realAdapterEmptyQueryIsNoDataAndFailureDoesNotFallback() throws Exception {
        var empty = OpenApiAssertions.validate("/api/v1/metrics/current", get("/api/v1/metrics/current?metric=node.memory.used&resourceId=empty"), 200);
        assertThat(empty.get("dataStatus").asString()).isEqualTo("no_data");
        var failure = OpenApiAssertions.validate("/api/v1/metrics/current", get("/api/v1/metrics/current?metric=node.memory.used&resourceId=broken"), 503);
        assertThat(failure.get("code").asString()).isEqualTo("METRICS_SOURCE_UNAVAILABLE");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/", exchange -> {
                String params = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                boolean history = exchange.getRequestURI().getPath().endsWith("query_range");
                String body;
                int status = 200;
                if (params.contains("node=\"broken\"")) {
                    status = 503;
                    body = "{\"status\":\"error\",\"errorType\":\"unavailable\"}";
                } else if (params.contains("node=\"empty\"")) {
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
                } else if (history) {
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"node\":\"host:9100\"},\"values\":[[120,\"0\"],[150,\"NaN\"]]}]}}";
                } else {
                    body = """
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"node":"host:9100"},"value":[200,"0"]},
                          {"metric":{"node":"host:9100","cluster":"other-lab","provenance":"simulated"},"value":[200,"1024"]}
                        ]}}
                        """;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
