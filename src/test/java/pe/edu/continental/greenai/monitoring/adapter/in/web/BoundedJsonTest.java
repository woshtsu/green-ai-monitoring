package pe.edu.continental.greenai.monitoring.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

class BoundedJsonTest {
    @Test void acceptsExactByteBudgetAndRejectsOverflowWithoutReturningPartialJson() {
        BoundedJson json = new BoundedJson(JsonMapper.builder().build());
        assertThat(json.encode("a".repeat(BoundedJson.MAX_BYTES - 2))).hasSize(BoundedJson.MAX_BYTES);
        assertThatThrownBy(() -> json.encode("a".repeat(BoundedJson.MAX_BYTES - 1)))
            .isInstanceOfSatisfying(MonitoringFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED));
        assertThatThrownBy(() -> json.encode("á".repeat(BoundedJson.MAX_BYTES / 2)))
            .isInstanceOf(MonitoringFailure.class);
    }
}
