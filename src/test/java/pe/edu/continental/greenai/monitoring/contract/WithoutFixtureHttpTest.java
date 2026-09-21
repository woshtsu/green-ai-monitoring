package pe.edu.continental.greenai.monitoring.contract;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=")
class WithoutFixtureHttpTest {
    @LocalServerPort int port;

    @Test void noProfileNeverEnablesFixtureAndCatalogRemainsAvailable() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String root = "/api/v1/metrics";
            for (String endpoint : new String[]{"/current?metric=node.memory.used",
                "/history?metric=node.memory.used&start=2026-01-01T00:00:00Z&end=2026-01-01T00:01:00Z"}) {
                String path = root + endpoint.split("\\?")[0];
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + root + endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString());
                var body = OpenApiAssertions.validate(path, response, 503);
                assertThat(body.get("code").asString()).isEqualTo("METRICS_SOURCE_UNAVAILABLE");
                assertThat(response.body()).doesNotContain("fixture-node");
            }
            var catalog = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + root + "/catalog")).GET().build(), HttpResponse.BodyHandlers.ofString());
            OpenApiAssertions.validate(root + "/catalog", catalog, 200);
        }
    }
}
