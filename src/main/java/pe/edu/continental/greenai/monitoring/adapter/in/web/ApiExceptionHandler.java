package pe.edu.continental.greenai.monitoring.adapter.in.web;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MonitoringFailure.class)
    public ResponseEntity<ProblemDetail> monitoring(MonitoringFailure failure, HttpServletRequest request) {
        int status = switch (failure.code()) {
            case INVALID_QUERY -> 400;
            case QUERY_LIMIT_EXCEEDED -> 422;
            case UPSTREAM_INVALID_RESPONSE -> 502;
            case METRICS_SOURCE_UNAVAILABLE -> 503;
            case METRICS_SOURCE_TIMEOUT -> 504;
        };
        return problem(status, failure.code().name(), failure.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> other(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            return problem(error.getStatusCode().value(), "HTTP_ERROR", "Solicitud HTTP no admitida.", request);
        }
        LOG.error("Unexpected Monitoring failure requestId={} exceptionType={}",
            request.getAttribute(RequestIdFilter.ATTRIBUTE), exception.getClass().getSimpleName());
        return problem(500, "INTERNAL_ERROR", "Error interno al procesar la consulta.", request);
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setType(URI.create("urn:green-ai:monitoring:error:" + code.toLowerCase(java.util.Locale.ROOT)));
        body.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        body.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
