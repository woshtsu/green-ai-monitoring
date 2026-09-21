package pe.edu.continental.greenai.monitoring.domain;

public final class MonitoringFailure extends RuntimeException {
    public enum Code {
        INVALID_QUERY, QUERY_LIMIT_EXCEEDED, UPSTREAM_INVALID_RESPONSE,
        METRICS_SOURCE_UNAVAILABLE, METRICS_SOURCE_TIMEOUT
    }

    private final Code code;

    public MonitoringFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    public static MonitoringFailure invalid(String message) {
        return new MonitoringFailure(Code.INVALID_QUERY, message);
    }

    public static MonitoringFailure limit(String message) {
        return new MonitoringFailure(Code.QUERY_LIMIT_EXCEEDED, message);
    }
}
