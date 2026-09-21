package pe.edu.continental.greenai.monitoring.adapter.in.web;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import pe.edu.continental.greenai.monitoring.domain.MonitoringFailure;

/** Buffers at most the allowed body before MVC commits headers; no partial successful response. */
@Component
public final class BoundedJson {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final JsonMapper mapper;

    public BoundedJson(JsonMapper mapper) { this.mapper = mapper; }

    public byte[] encode(Object value) {
        LimitedOutput output = new LimitedOutput();
        try {
            mapper.writeValue(output, value);
        } catch (RuntimeException exception) {
            if (output.exceeded) throw MonitoringFailure.limit("La respuesta supera 5 MiB.");
            throw exception;
        }
        return output.bytes.toByteArray();
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean exceeded;

        private void reserve(int count) {
            if (count > MAX_BYTES - bytes.size()) {
                exceeded = true;
                throw MonitoringFailure.limit("La respuesta supera 5 MiB.");
            }
        }

        @Override public void write(int value) { reserve(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int count) {
            reserve(count); bytes.write(value, offset, count);
        }
    }
}
