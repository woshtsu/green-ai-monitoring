package pe.edu.continental.greenai.monitoring.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.application.MonitoringService;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import java.time.Clock;

class ApiFailureMappingTest {
    @ParameterizedTest
    @CsvSource({"UPSTREAM_INVALID_RESPONSE,502", "METRICS_SOURCE_UNAVAILABLE,503", "METRICS_SOURCE_TIMEOUT,504", "QUERY_LIMIT_EXCEEDED,422"})
    void translatesSourceFailuresIntoProblemJson(MonitoringFailure.Code code, int status) throws Exception {
        var service = new MonitoringService(query -> { throw new MonitoringFailure(code, "Fallo controlado."); }, Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new MetricsController(service, new BoundedJson(JsonMapper.builder().build())))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        mvc.perform(get("/api/v1/metrics/current").param("metric", "node.memory.used"))
            .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(header().exists("X-Request-Id"));
    }
}
