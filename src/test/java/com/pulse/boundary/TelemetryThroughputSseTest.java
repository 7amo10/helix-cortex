package com.pulse.boundary;

import com.pulse.boundary.dto.StreamThroughputSnapshot;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.FlameGraphControl;
import com.pulse.control.StreamThroughputControl;
import com.pulse.control.TelemetryControl;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Task: Telemetry Throughput SSE REST Boundary Tests")
class TelemetryThroughputSseTest {

    private TelemetryControl telemetryControl;
    private FlameGraphControl flameGraphControl;
    private StreamThroughputControl throughputControl;
    private TelemetryResource resource;

    @BeforeEach
    void setUp() {
        telemetryControl = mock(TelemetryControl.class);
        flameGraphControl = mock(FlameGraphControl.class);
        throughputControl = mock(StreamThroughputControl.class);

        resource = new TelemetryResource(telemetryControl, flameGraphControl, throughputControl);
    }

    @Test
    @DisplayName("streamThroughput endpoint should be secured and allow ADMIN, ENGINEER, OPERATOR")
    void testSecurityAnnotationsOnStreamThroughput() throws Exception {
        assertThat(TelemetryResource.class.isAnnotationPresent(Secured.class)).isTrue();

        var method = TelemetryResource.class.getMethod("streamThroughput", SseEventSink.class, Sse.class);
        assertThat(method.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(method.getAnnotation(RolesAllowed.class).value())
                .containsExactlyInAnyOrder("ADMIN", "ENGINEER", "OPERATOR");
    }

    @Test
    @DisplayName("streamThroughput registers sink with throughput control")
    void testStreamThroughputRegistersSink() {
        SseEventSink sink = mock(SseEventSink.class);
        Sse sse = mock(Sse.class);

        resource.streamThroughput(sink, sse);

        verify(throughputControl, times(1)).registerSink(sink, sse);
    }

    @Test
    @DisplayName("getThroughputSnapshot returns 200 OK with snapshot entity")
    void testGetThroughputSnapshot() {
        StreamThroughputSnapshot snapshot = new StreamThroughputSnapshot(1500.0, 10, 2.5, 10000, 9990, 9990);
        when(throughputControl.currentSnapshot()).thenReturn(snapshot);

        Response response = resource.getThroughputSnapshot();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(snapshot);
    }
}
