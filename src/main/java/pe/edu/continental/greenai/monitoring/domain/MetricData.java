package pe.edu.continental.greenai.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MetricData {
    private MetricData() { }

    public enum Origin { observed, simulated, estimated, unknown }
    public enum Source { fixture, prometheus }
    public enum Quality { valid, missing, non_finite }
    public enum Status { complete, partial, no_data }

    public record Resource(String type, String cluster, String id) { }
    public record RawSample(Instant timestamp, double value) { }
    public record RawSeries(Resource resource, Map<String, String> labels, Source source,
                            Origin origin, List<RawSample> samples) {
        public RawSeries {
            labels = Map.copyOf(labels);
            samples = List.copyOf(samples);
        }
    }
    public record SourceResult(List<RawSeries> series, List<String> warnings) {
        public SourceResult { series = List.copyOf(series); warnings = List.copyOf(warnings); }
    }
    public record Sample(Instant timestamp, Double value, Quality quality) { }
    public record Series(Resource resource, Map<String, String> labels, Source source,
                         Origin origin, List<Sample> samples) {
        public Series { labels = Map.copyOf(labels); samples = List.copyOf(samples); }
    }
    public record Response(String metric, String unit, String aggregation, Integer windowSeconds,
                           Instant start, Instant end, Integer stepSeconds, Status dataStatus,
                           List<Series> series, List<String> warnings) {
        public Response { series = List.copyOf(series); warnings = List.copyOf(warnings); }
    }
}
