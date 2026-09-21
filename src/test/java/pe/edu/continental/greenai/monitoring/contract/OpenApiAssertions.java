package pe.edu.continental.greenai.monitoring.contract;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.UUID;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class OpenApiAssertions {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode SPEC = loadSpec();
    private OpenApiAssertions() { }

    private static JsonNode loadSpec() {
        try (var stream = OpenApiAssertions.class.getResourceAsStream("/static/openapi/monitoring-v0.1.json")) {
            return MAPPER.readTree(stream);
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public static JsonNode validate(String path, HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
        String media = expectedStatus == 200 ? "application/json" : "application/problem+json";
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith(media);
        JsonNode schema = SPEC.get("paths").get(path).get("get").get("responses")
            .get(Integer.toString(expectedStatus)).get("content").get(media).get("schema");
        // Embed the exact OpenAPI schemas as JSON Schema $defs, preserving internal references.
        ObjectNode root = (ObjectNode) MAPPER.readTree(schema.toString().replace("#/components/schemas/", "#/$defs/"));
        root.set("$defs", MAPPER.readTree(SPEC.get("components").get("schemas").toString()
            .replace("#/components/schemas/", "#/$defs/")));
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        var validator = registry.getSchema(root.toString(), InputFormat.JSON);
        assertThat(validator.validate(response.body(), InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
            .as("HTTP response must conform to the versioned OpenAPI schema").isEmpty();
        JsonNode body = MAPPER.readTree(response.body());
        if (expectedStatus != 200) {
            assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
            assertThat(body.get("requestId").asString()).isEqualTo(id);
            assertThat(body.get("instance").asString()).isEqualTo(path);
        }
        return body;
    }

    public static JsonNode specification() { return SPEC; }
}
