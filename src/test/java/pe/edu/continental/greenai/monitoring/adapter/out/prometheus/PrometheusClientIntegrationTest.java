package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

class PrometheusClientIntegrationTest {
    private HttpServer server;
    private ExecutorService executor;
    private PrometheusClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        client = new PrometheusClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/prometheus"), json);
    }

    @AfterEach void stop() { client.close(); server.stop(0); executor.shutdownNow(); }

    @Test void instantAndRangeSendControlledParametersAndPreserveBasePath() {
        AtomicReference<URI> captured = new AtomicReference<>();
        server.createContext("/prometheus", exchange -> {
            captured.set(exchange.getRequestURI());
            respond(exchange, 200, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        });
        client.query(PrometheusTestData.query("node.memory.used", false), "up{instance=\"a:9100\"}");
        assertThat(captured.get().getPath()).isEqualTo("/prometheus/api/v1/query");
        assertThat(URLDecoder.decode(captured.get().getRawQuery(), StandardCharsets.UTF_8))
            .contains("query=up{instance=\"a:9100\"}", "timeout=3s", "limit=101", "lookback_delta=30s", "time=120");
        client.query(PrometheusTestData.query("node.memory.used", true), "up");
        assertThat(captured.get().getPath()).isEqualTo("/prometheus/api/v1/query_range");
        assertThat(captured.get().getRawQuery()).contains("start=120&end=150&step=15");
    }

    @ParameterizedTest
    @CsvSource({"400,bad_data,UPSTREAM_INVALID_RESPONSE", "422,execution,UPSTREAM_INVALID_RESPONSE",
        "503,timeout,METRICS_SOURCE_TIMEOUT", "503,canceled,METRICS_SOURCE_TIMEOUT",
        "503,unavailable,METRICS_SOURCE_UNAVAILABLE", "429,unavailable,METRICS_SOURCE_UNAVAILABLE",
        "504,timeout,METRICS_SOURCE_TIMEOUT", "200,bad_data,UPSTREAM_INVALID_RESPONSE"})
    void mapsErrorsWithoutExposingInternalMessages(int status, String type, MonitoringFailure.Code expected) {
        server.createContext("/", exchange -> respond(exchange, status,
            "{\"status\":\"error\",\"errorType\":\"" + type + "\",\"error\":\"secret query internal URL\"}"));
        assertThatThrownBy(() -> client.query(PrometheusTestData.query("node.memory.used", false), "up"))
            .isInstanceOfSatisfying(MonitoringFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo(expected);
                assertThat(failure.getMessage()).doesNotContain("secret", "URL", "query");
            });
    }

    @Test void rejectsMalformedJsonAndRedirects() {
        var context = server.createContext("/", exchange -> respond(exchange, 200, "not JSON"));
        assertFailure(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE);
        server.removeContext(context);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
            respond(exchange, 302, "{}");
        });
        assertFailure(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE);
    }

    @Test void acceptsExactBodyLimitAndCancelsOversizedChunkedBody() {
        String success = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        var context = server.createContext("/", exchange -> respond(exchange, 200,
            success + " ".repeat(PrometheusClient.MAX_BODY_BYTES - success.length())));
        assertThat(client.query(PrometheusTestData.query("node.memory.used", false), "up").get("status").asString()).isEqualTo("success");
        server.removeContext(context);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked; Content-Length cannot enforce this limit
            try (var body = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 641; i++) body.write(chunk);
            } catch (IOException ignored) { /* expected cancellation by subscriber */ }
        });
        assertFailure(MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED);
    }

    @Test void timeoutIncludesStalledBodyAfterHeaders() {
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write('{'); body.flush();
                try { Thread.sleep(2000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            } catch (IOException ignored) { /* client canceled */ }
        });
        client.close();
        client = new PrometheusClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, Duration.ofMillis(500));
        assertFailure(MonitoringFailure.Code.METRICS_SOURCE_TIMEOUT);
    }

    @Test void mapsConnectionRefusedToUnavailable() throws IOException {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        client.close();
        client = new PrometheusClient(URI.create("http://127.0.0.1:" + unusedPort), json);
        assertFailure(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE);
    }

    private void assertFailure(MonitoringFailure.Code expected) {
        assertThatThrownBy(() -> client.query(PrometheusTestData.query("node.memory.used", false), "up"))
            .isInstanceOfSatisfying(MonitoringFailure.class, failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private void respond(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
}
