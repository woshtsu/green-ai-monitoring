package pe.edu.continental.greenai.monitoring.application;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.edu.continental.greenai.monitoring.adapter.out.fixture.FixtureMetricsSource;
import pe.edu.continental.greenai.monitoring.application.port.out.MetricsSource;
import pe.edu.continental.greenai.monitoring.domain.*;
import pe.edu.continental.greenai.monitoring.domain.MetricData.*;

class MonitoringServiceTest {
    private static final Instant START = Instant.parse("2026-09-21T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START.plusSeconds(86400), ZoneOffset.UTC);

    @Test void separatesZeroMissingNonFiniteAndPreservesMultipleSeries() {
        Response response = service(new FixtureMetricsSource()).history("node.memory.used", "node", null, null,
            START, START.plusSeconds(45), 15);
        assertThat(response.dataStatus()).isEqualTo(Status.partial);
        assertThat(response.series()).hasSize(2);
        assertThat(response.series().getFirst().samples()).containsExactly(
            new Sample(START, 0d, Quality.valid), new Sample(START.plusSeconds(15), null, Quality.missing),
            new Sample(START.plusSeconds(30), null, Quality.non_finite),
            new Sample(START.plusSeconds(45), 1_073_741_824d, Quality.valid));
        assertThat(response.warnings()).containsExactly("NON_FINITE_VALUE");
        assertThat(response.series()).allSatisfy(s -> {
            assertThat(s.origin()).isEqualTo(Origin.simulated);
            assertThat(s.source()).isEqualTo(Source.fixture);
        });
    }

    @Test void doesNotInventSeriesForUnknownResource() {
        Response response = service(new FixtureMetricsSource()).current("node.cpu.utilization", "node", "absent", null);
        assertThat(response.series()).isEmpty();
        assertThat(response.dataStatus()).isEqualTo(Status.no_data);
    }

    @Test void filtersResourceAndPreservesFilesystemIdentity() {
        Response response = service(new FixtureMetricsSource()).current("node.filesystem.used", "node", "fixture-lab", "fixture-node-02");
        assertThat(response.series()).hasSize(2).allSatisfy(s -> {
            assertThat(s.resource().id()).isEqualTo("fixture-node-02");
            assertThat(s.labels()).containsKeys("device", "mountpoint", "fstype");
            assertThat(s.labels().get("fstype")).isIn("ext4", "xfs");
        });
        assertThat(response.series()).extracting(s -> s.labels().get("mountpoint")).containsExactly("/", "/data");
        assertThat(response.unit()).isEqualTo("bytes");
    }

    @Test void gridIsInclusiveWithoutExtendingToUnalignedEnd() {
        Response response = service(new FixtureMetricsSource()).history("node.memory.used", "node", null, "fixture-node-02",
            START, START.plusSeconds(31), 15);
        assertThat(response.series().getFirst().samples()).extracting(Sample::timestamp)
            .containsExactly(START, START.plusSeconds(15), START.plusSeconds(30));
    }

    @Test void capturesClockOnceAndTruncatesCurrentToSeconds() {
        Clock clock = Clock.fixed(START.plusNanos(123), ZoneOffset.UTC);
        Response response = new MonitoringService(new FixtureMetricsSource(), clock)
            .current("node.memory.used", "node", null, "fixture-node-02");
        assertThat(response.start()).isEqualTo(START);
        assertThat(response.end()).isEqualTo(START);
        assertThat(response.stepSeconds()).isNull();
        assertThat(response.series().getFirst().samples()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(ints = {0, 14, 3601, Integer.MAX_VALUE})
    void rejectsResolutionBeforeCallingSource(int step) {
        assertFailure(() -> service(q -> { throw new AssertionError("Source must not be called"); })
            .history("node.memory.used", "node", null, null, START, START.plusSeconds(60), step),
            MonitoringFailure.Code.INVALID_QUERY);
    }

    @Test void rejectsFutureReversedAndFractionalIntervals() {
        MonitoringService service = service(q -> { throw new AssertionError("Source must not be called"); });
        assertFailure(() -> service.history("node.memory.used", "node", null, null, START, CLOCK.instant().plusSeconds(1), 15), MonitoringFailure.Code.INVALID_QUERY);
        assertFailure(() -> service.history("node.memory.used", "node", null, null, START, START, 15), MonitoringFailure.Code.INVALID_QUERY);
        assertFailure(() -> service.history("node.memory.used", "node", null, null, START.plusNanos(1), START.plusSeconds(30), 15), MonitoringFailure.Code.INVALID_QUERY);
        assertFailure(() -> service.history("node.memory.used", "node", null, null, START.minusSeconds(1), CLOCK.instant(), 15), MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED);
    }

    @Test void accepts24HoursAndRejectsTotalPointsBeforeExpansion() {
        MonitoringService service = service(new FixtureMetricsSource());
        assertThat(service.history("node.memory.used", "node", null, "fixture-node-02", START, CLOCK.instant(), 15)
            .series().getFirst().samples()).hasSize(5761);
        assertFailure(() -> service.history("node.memory.used", "node", null, null, START, CLOCK.instant(), 15),
            MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED);
    }

    @Test void exactly100SeriesAreAllowedAnd101AreRejected() {
        MetricsSource hundred = q -> new SourceResult(IntStream.range(0, 100).mapToObj(i -> raw("n" + i,
            List.of(new RawSample(q.start(), 1)))).toList(), List.of());
        assertThat(service(hundred).current("node.memory.used", "node", null, null).series()).hasSize(100);
        MetricsSource tooMany = q -> new SourceResult(Collections.nCopies(101, raw("n", List.of(new RawSample(q.start(), 1)))), List.of());
        assertFailure(() -> service(tooMany).current("node.memory.used", "node", null, null), MonitoringFailure.Code.QUERY_LIMIT_EXCEEDED);
    }

    @ParameterizedTest @ValueSource(strings = {"cpu", "sum(up)", "", "service.http.latency"})
    void disallowsArbitraryOrNotYetRegisteredMetrics(String metric) {
        assertFailure(() -> service(new FixtureMetricsSource()).current(metric, "node", null, null), MonitoringFailure.Code.INVALID_QUERY);
    }

    @Test void disallowsResourceTypeMismatchAndSelectorInjection() {
        MonitoringService service = service(new FixtureMetricsSource());
        assertFailure(() -> service.current("node.cpu.utilization", "pod", null, null), MonitoringFailure.Code.INVALID_QUERY);
        assertFailure(() -> service.current("node.cpu.utilization", "node", "x\"} or up", null), MonitoringFailure.Code.INVALID_QUERY);
        assertFailure(() -> service.current("node.cpu.utilization", "node", "a".repeat(129), null), MonitoringFailure.Code.INVALID_QUERY);
    }

    @Test void rejectsDuplicateSeriesAndOffGridSamples() {
        RawSeries item = raw("node", List.of(new RawSample(START, 1)));
        assertFailure(() -> service(q -> new SourceResult(List.of(item, item), List.of()))
            .history("node.memory.used", "node", null, null, START, START.plusSeconds(30), 15), MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE);
        assertFailure(() -> service(q -> new SourceResult(List.of(raw("node", List.of(new RawSample(START.plusSeconds(1), 1)))), List.of()))
            .history("node.memory.used", "node", null, null, START, START.plusSeconds(30), 15), MonitoringFailure.Code.UPSTREAM_INVALID_RESPONSE);
    }

    @Test void sanitizesWarningsAndInfinity() {
        Response response = service(q -> new SourceResult(List.of(raw("n", List.of(new RawSample(q.start(), Double.POSITIVE_INFINITY)))),
            List.of("http://private/secret?query=up"))).current("node.memory.used", "node", null, null);
        assertThat(response.warnings()).containsExactly("SOURCE_WARNING", "NON_FINITE_VALUE");
        assertThat(response.series().getFirst().samples().getFirst().value()).isNull();
    }

    @Test void fixtureIsDeterministicForSameRequest() {
        MonitoringService service = service(new FixtureMetricsSource());
        assertThat(service.current("node.network.receive", "node", null, null))
            .isEqualTo(service.current("node.network.receive", "node", null, null));
    }

    private MonitoringService service(MetricsSource source) { return new MonitoringService(source, CLOCK); }
    private RawSeries raw(String node, List<RawSample> samples) {
        return new RawSeries(new Resource("node", "cluster", node), Map.of(), Source.prometheus, Origin.observed, samples);
    }
    private void assertFailure(Runnable action, MonitoringFailure.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MonitoringFailure.class,
            failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
