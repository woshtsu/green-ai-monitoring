package pe.edu.continental.greenai.monitoring.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

final class QueryParameters {
    private static final Set<String> COMMON = Set.of("metric", "resourceType", "cluster", "resourceId");
    private static final Set<String> HISTORY = Set.of("metric", "resourceType", "cluster", "resourceId", "start", "end", "stepSeconds");
    private static final Pattern TIMESTAMP = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})");
    private final MultiValueMap<String, String> values;

    QueryParameters(MultiValueMap<String, String> values, boolean history) {
        validate(values, history ? HISTORY : COMMON);
        this.values = values;
    }

    static void validate(MultiValueMap<String, String> values, Set<String> allowed) {
        values.forEach((key, entries) -> {
            if (!allowed.contains(key) || entries.size() != 1 || entries.getFirst() == null
                || entries.getFirst().isBlank() || entries.getFirst().length() > 128) {
                throw MonitoringFailure.invalid("Parámetros desconocidos, repetidos o inválidos.");
            }
        });
    }

    String required(String name) {
        String value = values.getFirst(name);
        if (value == null) throw MonitoringFailure.invalid("Falta un parámetro obligatorio: " + name + ".");
        return value;
    }

    String optional(String name) { return values.getFirst(name); }
    String resourceType() { return values.getFirst("resourceType") == null ? "node" : values.getFirst("resourceType"); }

    Instant instant(String name) {
        String value = required(name);
        try {
            if (!TIMESTAMP.matcher(value).matches() || value.endsWith("-00:00")) throw new IllegalArgumentException();
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            throw MonitoringFailure.invalid("Timestamp inválido: use RFC3339 con zona conocida y segundos enteros.");
        }
    }

    int stepSeconds() {
        String value = values.getFirst("stepSeconds");
        if (value == null) return 15;
        try {
            if (!value.matches("[0-9]{1,4}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw MonitoringFailure.invalid("stepSeconds debe ser un entero entre 15 y 3600.");
        }
    }
}
