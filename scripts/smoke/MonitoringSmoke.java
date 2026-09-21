import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Executes inside JDK 21 against the packaged JAR and real Prometheus/Node Exporter. */
class MonitoringSmoke {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ROOT = "http://monitoring:8080/api/v1/metrics/";
    static final List<String> METRICS = List.of("node.cpu.utilization", "node.memory.used",
        "node.network.receive", "node.network.transmit", "node.filesystem.used");

    public static void main(String[] args) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            if (args.length > 0 && args[0].equals("unavailable")) {
                var response = get(client, ROOT + "current?metric=node.memory.used");
                check(response.statusCode() == 503, "Upstream stopped must produce 503, got " + response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                check(body.path("code").asString().equals("METRICS_SOURCE_UNAVAILABLE"), "No fixture fallback");
                Files.writeString(Path.of("/evidence/unavailable.json"), response.body());
                System.out.println("PASS upstream stopped -> 503 without fixture fallback");
                return;
            }
            // Wait for application startup and enough real scrapes to evaluate rates.
            Instant deadline = Instant.now().plusSeconds(90);
            String last = "No response";
            boolean ready = false;
            while (Instant.now().isBefore(deadline)) {
                try {
                    ready = true;
                    for (String metric : METRICS) {
                        var response = get(client, ROOT + "current?metric=" + metric);
                        last = metric + " -> " + response.statusCode() + " " + response.body();
                        ready &= response.statusCode() == 200 && !JSON.readTree(response.body()).path("series").isEmpty();
                    }
                    if (ready) break;
                } catch (Exception exception) { last = exception.toString(); }
                Thread.sleep(2000);
            }
            check(ready, "Real metrics not ready: " + last);
            var catalog = get(client, ROOT + "catalog");
            check(catalog.statusCode() == 200 && JSON.readTree(catalog.body()).size() == 5, "Catalog");
            for (String metric : METRICS) {
                String filters = "metric=" + metric + "&cluster=smoke-lab&resourceId=exporter:9100";
                var response = get(client, ROOT + "current?" + filters);
                validate(response, metric);
                Files.writeString(Path.of("/evidence/" + metric + "-current.json"), response.body());
                long end = Instant.now().getEpochSecond();
                String range = "&start=" + Instant.ofEpochSecond(end - 15) + "&end=" + Instant.ofEpochSecond(end) + "&stepSeconds=15";
                var history = get(client, ROOT + "history?" + filters + range);
                validate(history, metric);
                Files.writeString(Path.of("/evidence/" + metric + "-history.json"), history.body());
                System.out.println("PASS real PromQL current/history " + metric);
            }
            var absent = get(client, ROOT + "current?metric=node.memory.used&cluster=other-lab");
            check(absent.statusCode() == 200 && JSON.readTree(absent.body()).path("dataStatus").asString().equals("no_data"), "Cluster filtering");
            String prom = "http://prometheus:9090/api/v1/query?query=";
            boolean scrapeUp = false;
            Instant scrapeDeadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(scrapeDeadline)) {
                JsonNode scrape = JSON.readTree(get(client, prom + URLEncoder.encode("up{job=\"smoke-monitoring\"}", StandardCharsets.UTF_8)).body());
                JsonNode results = scrape.path("data").path("result");
                scrapeUp = !results.isEmpty() && results.get(0).path("value").get(1).asString().equals("1");
                if (scrapeUp) break;
                Thread.sleep(1000);
            }
            var targets = get(client, "http://prometheus:9090/api/v1/targets");
            Files.writeString(Path.of("/evidence/prometheus-targets.json"), targets.body());
            check(scrapeUp, "Monitoring technical scrape up: " + targets.body());
            var technical = get(client, "http://monitoring:8080/actuator/prometheus");
            check(technical.statusCode() == 200 && technical.body().contains("jvm_memory_used_bytes"), "Actuator technical metrics");
            System.out.println("PASS real scrape, cluster filtering and technical metrics");
        }
    }

    static void validate(HttpResponse<String> response, String metric) {
        check(response.statusCode() == 200, metric + " HTTP " + response.statusCode() + " " + response.body());
        check(response.headers().firstValue("X-Request-Id").isPresent(), "Request ID");
        JsonNode body = JSON.readTree(response.body());
        check(!body.path("series").isEmpty(), metric + " empty series");
        for (JsonNode series : body.path("series")) {
            check(series.path("source").asString().equals("prometheus"), "Real source");
            check(series.path("origin").asString().equals("observed"), "Explicit observed scrape label");
            check(series.path("resource").path("cluster").asString().equals("smoke-lab"), "Default cluster");
            check(series.path("resource").path("id").asString().equals("exporter:9100"), "Resource identity");
            boolean hasValid = false;
            for (JsonNode sample : series.path("samples")) {
                if (sample.path("quality").asString().equals("valid")) {
                    check(sample.path("value").isNumber(), "Valid numeric sample");
                    hasValid = true;
                }
            }
            check(hasValid, "At least one observed value");
            if (metric.equals("node.filesystem.used")) {
                for (String label : List.of("device", "mountpoint", "fstype")) check(series.path("labels").has(label), "Filesystem " + label);
            }
        }
    }

    static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
