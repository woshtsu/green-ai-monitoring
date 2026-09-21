package pe.edu.continental.greenai.monitoring.adapter.out.prometheus;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import static pe.edu.continental.greenai.monitoring.domain.MonitoringFailure.Code.*;

public final class PrometheusClient implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private final URI baseUrl;
    private final HttpClient http;
    private final JsonMapper json;
    private final Duration requestTimeout;

    public PrometheusClient(URI baseUrl, JsonMapper json) {
        this(baseUrl, json, Duration.ofSeconds(3));
    }

    // Package-private timeout override lets integration tests exercise delayed bodies without long sleeps.
    PrometheusClient(URI baseUrl, JsonMapper json, Duration requestTimeout) {
        this.baseUrl = baseUrl;
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode query(MetricQuery query, String expression) {
        String base = baseUrl.toString().replaceAll("/+$", "");
        String parameters = "query=" + URLEncoder.encode(expression, StandardCharsets.UTF_8)
            + "&timeout=3s&limit=101&lookback_delta=30s";
        String endpoint;
        if (query.stepSeconds() == null) {
            endpoint = "/api/v1/query";
            parameters += "&time=" + query.start().getEpochSecond();
        } else {
            endpoint = "/api/v1/query_range";
            parameters += "&start=" + query.start().getEpochSecond() + "&end=" + query.end().getEpochSecond()
                + "&step=" + query.stepSeconds();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + endpoint + "?" + parameters))
            .header("Accept", "application/json").timeout(requestTimeout).GET().build();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new LimitedSubscriber());
        HttpResponse<byte[]> response;
        try {
            // Unlike returning an InputStream, completion here includes the full bounded body.
            response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw failure(METRICS_SOURCE_TIMEOUT);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(METRICS_SOURCE_UNAVAILABLE);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof MonitoringFailure limit) throw limit;
                if (cause instanceof HttpTimeoutException) throw failure(METRICS_SOURCE_TIMEOUT);
                cause = cause.getCause();
            }
            throw failure(METRICS_SOURCE_UNAVAILABLE);
        }
        JsonNode envelope;
        try {
            envelope = json.readTree(response.body());
        } catch (RuntimeException exception) {
            if (response.statusCode() == 504) throw failure(METRICS_SOURCE_TIMEOUT);
            if (response.statusCode() == 429 || response.statusCode() >= 500) throw failure(METRICS_SOURCE_UNAVAILABLE);
            throw failure(UPSTREAM_INVALID_RESPONSE);
        }
        if (envelope == null || !envelope.isObject()) {
            if (response.statusCode() == 504) throw failure(METRICS_SOURCE_TIMEOUT);
            if (response.statusCode() == 429 || response.statusCode() >= 500) throw failure(METRICS_SOURCE_UNAVAILABLE);
            throw failure(UPSTREAM_INVALID_RESPONSE);
        }
        String errorType = envelope.path("errorType").asString("");
        if (response.statusCode() == 504 || errorType.equals("timeout") || errorType.equals("canceled")) {
            throw failure(METRICS_SOURCE_TIMEOUT);
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) throw failure(METRICS_SOURCE_UNAVAILABLE);
        if (response.statusCode() != 200 || !envelope.path("status").asString("").equals("success")
            || !envelope.path("data").isObject()) throw failure(UPSTREAM_INVALID_RESPONSE);
        return envelope;
    }

    static MonitoringFailure failure(MonitoringFailure.Code code) {
        return new MonitoringFailure(code, switch (code) {
            case METRICS_SOURCE_TIMEOUT -> "Se agotó el tiempo de consulta de Prometheus.";
            case METRICS_SOURCE_UNAVAILABLE -> "Prometheus no está disponible.";
            case QUERY_LIMIT_EXCEEDED -> "La respuesta de Prometheus supera los límites.";
            default -> "Prometheus devolvió una respuesta inválida.";
        });
    }

    @Override public void close() { http.shutdownNow(); }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    result.completeExceptionally(failure(QUERY_LIMIT_EXCEEDED));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
