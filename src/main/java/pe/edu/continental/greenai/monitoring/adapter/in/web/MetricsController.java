package pe.edu.continental.greenai.monitoring.adapter.in.web;

import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import pe.edu.continental.greenai.monitoring.application.port.in.QueryMetrics;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {
    private final QueryMetrics queries;
    private final BoundedJson json;

    public MetricsController(QueryMetrics queries, BoundedJson json) {
        this.queries = queries;
        this.json = json;
    }

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> catalog(@RequestParam MultiValueMap<String, String> values) {
        QueryParameters.validate(values, Set.of());
        return response(queries.catalog());
    }

    @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> current(@RequestParam MultiValueMap<String, String> values) {
        QueryParameters params = new QueryParameters(values, false);
        return response(queries.current(params.required("metric"), params.resourceType(),
            params.optional("cluster"), params.optional("resourceId")));
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> history(@RequestParam MultiValueMap<String, String> values) {
        QueryParameters params = new QueryParameters(values, true);
        return response(queries.history(params.required("metric"), params.resourceType(), params.optional("cluster"),
            params.optional("resourceId"), params.instant("start"), params.instant("end"), params.stepSeconds()));
    }

    private ResponseEntity<byte[]> response(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.encode(value));
    }
}
