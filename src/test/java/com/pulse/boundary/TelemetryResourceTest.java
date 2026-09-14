package com.pulse.boundary;

import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.TelemetryControl;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryResourceTest {

    @Mock
    private TelemetryControl telemetryControl;

    @Mock
    private SseEventSink sink;

    @Mock
    private Sse sse;

    private TelemetryResource resource;

    @BeforeEach
    void setUp() {
        resource = new TelemetryResource(telemetryControl);
    }

    @Test
    @DisplayName("Class and method annotations adhere to SSE and RBAC specifications")
    void testAnnotations() throws NoSuchMethodException {
        assertThat(TelemetryResource.class.isAnnotationPresent(Path.class)).isTrue();
        assertThat(TelemetryResource.class.getAnnotation(Path.class).value()).isEqualTo("/telemetry");
        assertThat(TelemetryResource.class.isAnnotationPresent(Secured.class)).isTrue();

        Method streamMethod = TelemetryResource.class.getMethod("stream", SseEventSink.class, Sse.class);
        assertThat(streamMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(streamMethod.isAnnotationPresent(Produces.class)).isTrue();
        assertThat(streamMethod.getAnnotation(Produces.class).value()).contains(MediaType.SERVER_SENT_EVENTS);
        assertThat(streamMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(streamMethod.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");

        Method snapshotMethod = TelemetryResource.class.getMethod("getSnapshot");
        assertThat(snapshotMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(snapshotMethod.isAnnotationPresent(Produces.class)).isTrue();
        assertThat(snapshotMethod.getAnnotation(Produces.class).value()).contains(MediaType.APPLICATION_JSON);
        assertThat(snapshotMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(snapshotMethod.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("stream method delegates event sink registration to TelemetryControl")
    void testStreamRegistersSink() {
        resource.stream(sink, sse);
        verify(telemetryControl).registerSink(sink);
    }

    @Test
    @DisplayName("getSnapshot returns 200 OK with JvmTelemetrySnapshot payload containing heapUsedBytes > 0")
    void testGetSnapshot() {
        JvmTelemetrySnapshot mockSnapshot = new JvmTelemetrySnapshot(
                104857600L, 1073741824L, 9.76, 5L, 20L, 10, 4, 60000L, Instant.now()
        );
        when(telemetryControl.currentSnapshot()).thenReturn(mockSnapshot);

        Response response = resource.getSnapshot();

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isInstanceOf(JvmTelemetrySnapshot.class);
        JvmTelemetrySnapshot snapshot = (JvmTelemetrySnapshot) response.getEntity();
        assertThat(snapshot.heapUsedBytes()).isGreaterThan(0L);
        assertThat(snapshot.heapMaxBytes()).isGreaterThan(0L);
        assertThat(snapshot.heapUsedPercent()).isGreaterThan(0.0);
        assertThat(snapshot.threadCount()).isGreaterThan(0);
        assertThat(snapshot.uptimeMs()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("RolesAllowed enforces ADMIN on stream and snapshot endpoints")
    void testRolesAllowedConfiguration() throws NoSuchMethodException {
        Method streamMethod = TelemetryResource.class.getMethod("stream", SseEventSink.class, Sse.class);
        RolesAllowed streamRoles = streamMethod.getAnnotation(RolesAllowed.class);
        assertThat(streamRoles.value()).containsExactly("ADMIN");
        assertThat(streamRoles.value()).doesNotContain("ENGINEER");

        Method snapshotMethod = TelemetryResource.class.getMethod("getSnapshot");
        RolesAllowed snapshotRoles = snapshotMethod.getAnnotation(RolesAllowed.class);
        assertThat(snapshotRoles.value()).containsExactly("ADMIN");
        assertThat(snapshotRoles.value()).doesNotContain("ENGINEER");
    }

    @Test
    @DisplayName("Telemetry streaming emits consecutive events to registered sink via control")
    void testStreamingConsecutiveEvents() {
        SseBroadcaster mockBroadcaster = mock(SseBroadcaster.class);
        OutboundSseEvent.Builder builder = mock(OutboundSseEvent.Builder.class);
        OutboundSseEvent event1 = mock(OutboundSseEvent.class);
        OutboundSseEvent event2 = mock(OutboundSseEvent.class);

        when(sse.newEventBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.id(anyString())).thenReturn(builder);
        when(builder.mediaType(any(MediaType.class))).thenReturn(builder);
        when(builder.data(any(Class.class), any())).thenReturn(builder);
        when(builder.build()).thenReturn(event1, event2);

        TelemetryControl liveControl = new TelemetryControl(sse, null, mockBroadcaster);
        TelemetryResource liveResource = new TelemetryResource(liveControl);

        // Register client sink
        liveResource.stream(sink, sse);
        verify(mockBroadcaster).register(sink);

        // Emit 2 telemetry snapshots
        liveControl.sampleAndBroadcast();
        liveControl.sampleAndBroadcast();

        verify(mockBroadcaster, times(2)).broadcast(any(OutboundSseEvent.class));
    }
}
