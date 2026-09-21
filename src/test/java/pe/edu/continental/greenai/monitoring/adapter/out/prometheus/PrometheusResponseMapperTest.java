package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.configuration.PrometheusProperties;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.*;

class PrometheusResponseMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PrometheusResponseMapper mapper = new PrometheusResponseMapper(PrometheusTestData.properties(null));

    @Test void defaultsClusterWithoutInventingObservedAndPreservesInstancePort() {
        var result = mapper.map(json.readTree(PrometheusTestData.vector("\"instance\":\"host:9100\"", "0")), PrometheusTestData.query("node.memory.used", false));
        var series = result.series().getFirst();
        assertThat(series.resource()).isEqualTo(new Resource("node", "lab-01", "host:9100"));
        assertThat(series.origin()).isEqualTo(Origin.unknown);
        assertThat(series.source()).isEqualTo(Source.prometheus);
        assertThat(series.samples().getFirst().value()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"observed", "simulated", "estimated", "unknown"})
    void respectsProvenanceAndClusterLabels(String origin) {
        var result = mapper.map(json.readTree(PrometheusTestData.vector("\"instance\":\"host:9100\",\"cluster\":\"other\",\"origin\":\"" + origin + "\"", "2")),
            PrometheusTestData.query("node.memory.used", false));
        assertThat(result.series().getFirst().origin()).isEqualTo(Origin.valueOf(origin));
        assertThat(result.series().getFirst().resource().cluster()).isEqualTo("other");
    }

    @Test void supportsExplicitProvenanceDefaultAndStableNodeLabel() {
        var config = new PrometheusProperties(null, "site", "default-site", "node", "provenance", Origin.observed);
        var result = new PrometheusResponseMapper(config).map(json.readTree(PrometheusTestData.vector("\"node\":\"host\"", "1")),
            PrometheusTestData.query("node.memory.used", false));
        assertThat(result.series().getFirst().origin()).isEqualTo(Origin.observed);
        assertThat(result.series().getFirst().resource().id()).isEqualTo("host");
    }

    @Test void parsesMatrixNonFiniteValuesWithoutFillingGapsInAdapter() {
        var envelope = json.readTree("""
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"instance":"node:9100","device":"eth0"},"values":[[120,"NaN"],[135,"+Inf"],[150,"-Inf"]]}
            ]},"warnings":["internal query text"],"infos":["info"]}
            """);
        var result = mapper.map(envelope, PrometheusTestData.query("node.network.receive", true));
        assertThat(result.series().getFirst().samples()).extracting(RawSample::value)
            .containsExactly(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertThat(result.series().getFirst().labels()).containsEntry("device", "eth0");
        assertThat(result.warnings()).containsExactly("PROMETHEUS_WARNINGS", "PROMETHEUS_INFOS");
    }

    @Test void filesystemPreservesAllDimensions() {
        var result = mapper.map(json.readTree(PrometheusTestData.vector("\"instance\":\"node:9100\",\"device\":\"/dev/sda\",\"mountpoint\":\"/data\",\"fstype\":\"xfs\"", "1024")),
            PrometheusTestData.query("node.filesystem.used", false));
        assertThat(result.series().getFirst().labels()).containsEntry("device", "/dev/sda").containsEntry("mountpoint", "/data").containsEntry("fstype", "xfs");
    }

    @ParameterizedTest @ValueSource(strings = {
        "{}", "{\"data\":{\"resultType\":\"scalar\",\"result\":[120,\"0\"]}}",
        "{\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[120,\"0\"]}]}}",
        "{\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"instance\":\"node\"},\"value\":[120.1,\"0\"]}]}}",
        "{\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"instance\":\"node\"},\"value\":[120,\"oops\"]}]}}"
    })
    void rejectsInvalidResultShapes(String input) {
        assertThatThrownBy(() -> mapper.map(json.readTree(input), PrometheusTestData.query("node.memory.used", false)))
            .isInstanceOfSatisfying(MonitoringFailure.class, failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE));
    }

    @Test void rejectsTooManySeriesBeforeMappingAndDoesNotTruncate() {
        var root = json.createObjectNode();
        var data = root.putObject("data").put("resultType", "vector");
        var series = data.putArray("result");
        for (int i = 0; i < 101; i++) series.addObject();
        assertThatThrownBy(() -> mapper.map(root, PrometheusTestData.query("node.memory.used", false)))
            .isInstanceOfSatisfying(MonitoringFailure.class, failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED));
    }

    @Test void allowsEmptyResultsAsNoData() {
        assertThat(mapper.map(json.readTree("{\"data\":{\"resultType\":\"vector\",\"result\":[]}}"),
            PrometheusTestData.query("node.memory.used", false)).series()).isEmpty();
    }
}
