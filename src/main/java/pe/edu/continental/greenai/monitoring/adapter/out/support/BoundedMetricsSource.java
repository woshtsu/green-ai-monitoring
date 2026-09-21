package pe.edu.continental.greenai.monitoring.adapter.out.support;

import java.time.Duration;
import java.util.concurrent.*;
import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.MetricQuery;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;
import pe.edu.continental.greenai.monitoring.domain.MetricData.SourceResult;

/** No queue: even a non-cooperative adapter cannot accumulate unlimited active tasks. */
public final class BoundedMetricsSource implements MetricsSource, AutoCloseable {
    private final MetricsSource delegate;
    private final Duration timeout;
    private final ExecutorService executor;

    public BoundedMetricsSource(MetricsSource delegate, Duration timeout, int concurrency) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("metrics-query-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public SourceResult query(MetricQuery query) {
        Future<SourceResult> task;
        try {
            task = executor.submit(() -> delegate.query(query));
        } catch (RejectedExecutionException exception) {
            throw new MonitoringFailure(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE,
                "La fuente está temporalmente ocupada.");
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new MonitoringFailure(MonitoringFailure.Code.METRICS_SOURCE_TIMEOUT,
                "Se agotó el tiempo de consulta.");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new MonitoringFailure(MonitoringFailure.Code.METRICS_SOURCE_UNAVAILABLE,
                "Consulta interrumpida.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof MonitoringFailure failure) throw failure;
            throw new MonitoringFailure(MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE,
                "La fuente no pudo entregar una respuesta válida.");
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
