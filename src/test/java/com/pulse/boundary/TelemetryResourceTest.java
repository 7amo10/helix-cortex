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

    @Mock
    private com.pulse.control.FlameGraphControl flameGraphControl;

    private TelemetryResource resource;

    @BeforeEach
    void setUp() {
        resource = new TelemetryResource(telemetryControl, flameGraphControl);
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

    @Test
    @DisplayName("FlameGraph endpoints have proper Path, Produces, and RolesAllowed annotations")
    void testFlameGraphAnnotations() throws NoSuchMethodException {
        Method flameGraphMethod = TelemetryResource.class.getMethod("getFlameGraph", String.class, String.class, jakarta.ws.rs.core.HttpHeaders.class);
        assertThat(flameGraphMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(flameGraphMethod.isAnnotationPresent(Path.class)).isTrue();
        assertThat(flameGraphMethod.getAnnotation(Path.class).value()).isEqualTo("/flamegraph");
        assertThat(flameGraphMethod.isAnnotationPresent(Produces.class)).isTrue();
        assertThat(flameGraphMethod.getAnnotation(Produces.class).value()).contains(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.TEXT_HTML);
        assertThat(flameGraphMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(flameGraphMethod.getAnnotation(RolesAllowed.class).value()).contains("ADMIN", "ENGINEER");

        Method sseMethod = TelemetryResource.class.getMethod("streamFlameGraph", SseEventSink.class, Sse.class, String.class, String.class);
        assertThat(sseMethod.isAnnotationPresent(GET.class)).isTrue();
        assertThat(sseMethod.isAnnotationPresent(Path.class)).isTrue();
        assertThat(sseMethod.getAnnotation(Path.class).value()).isEqualTo("/flamegraph/stream");
        assertThat(sseMethod.isAnnotationPresent(Produces.class)).isTrue();
        assertThat(sseMethod.getAnnotation(Produces.class).value()).contains(MediaType.SERVER_SENT_EVENTS);
        assertThat(sseMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(sseMethod.getAnnotation(RolesAllowed.class).value()).contains("ADMIN", "ENGINEER");
    }

    @Test
    @DisplayName("getFlameGraph format=text returns plain-text folded stack lines")
    void testGetFlameGraphPlainText() {
        when(flameGraphControl.getFoldedText(com.helix.profiler.flamegraph.MetricType.CPU_TIME))
                .thenReturn("main;foo;bar 42\nmain;foo;baz 18\n");

        Response resp = resource.getFlameGraph("CPU_TIME", "text", null);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE);
        assertThat(resp.getEntity()).isEqualTo("main;foo;bar 42\nmain;foo;baz 18\n");
    }

    @Test
    @DisplayName("getFlameGraph format=d3 returns d3 hierarchical FlameGraphNodeDto")
    void testGetFlameGraphD3() {
        com.pulse.boundary.dto.FlameGraphNodeDto d3Tree = new com.pulse.boundary.dto.FlameGraphNodeDto(
                "root", 100L, java.util.List.of()
        );
        when(flameGraphControl.getD3Tree(com.helix.profiler.flamegraph.MetricType.CPU_TIME)).thenReturn(d3Tree);

        Response resp = resource.getFlameGraph("CPU_TIME", "d3", null);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(resp.getEntity()).isEqualTo(d3Tree);
    }

    @Test
    @DisplayName("getFlameGraph format=speedscope returns Speedscope JSON structure")
    void testGetFlameGraphSpeedscope() {
        java.util.Map<String, Object> speedscopeJson = java.util.Map.of(
                "$schema", "https://www.speedscope.app/file-format-spec.json",
                "version", "0.1.2"
        );
        when(flameGraphControl.getSpeedscopeJson(com.helix.profiler.flamegraph.MetricType.CPU_TIME)).thenReturn(speedscopeJson);

        Response resp = resource.getFlameGraph("CPU_TIME", "speedscope", null);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(resp.getEntity()).isEqualTo(speedscopeJson);
    }

    @Test
    @DisplayName("getFlameGraph format=html returns self-contained HTML flame graph")
    void testGetFlameGraphHtml() {
        when(flameGraphControl.getHtmlFlameGraph(com.helix.profiler.flamegraph.MetricType.CPU_TIME))
                .thenReturn("<!DOCTYPE html><html><body>Flame Graph</body></html>");

        Response resp = resource.getFlameGraph("CPU_TIME", "html", null);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.TEXT_HTML_TYPE);
        assertThat(resp.getEntity()).asString().contains("<!DOCTYPE html>");
    }

    @Test
    @DisplayName("getFlameGraph default JSON returns FlameGraphResponse")
    void testGetFlameGraphDefaultJson() {
        com.pulse.boundary.dto.FlameGraphResponse mockResponse = new com.pulse.boundary.dto.FlameGraphResponse(
                "CPU_TIME", "samples", 100L, 2, 3,
                java.util.List.of("main;foo 100"),
                new com.pulse.boundary.dto.FlameGraphNodeDto("root", 100L, java.util.List.of()),
                java.util.Map.of("version", "0.1.2"),
                Instant.now()
        );
        when(flameGraphControl.getFlameGraphResponse(com.helix.profiler.flamegraph.MetricType.CPU_TIME))
                .thenReturn(mockResponse);

        Response resp = resource.getFlameGraph("CPU_TIME", null, null);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(resp.getEntity()).isEqualTo(mockResponse);
    }

    @Test
    @DisplayName("streamFlameGraph delegates registration to FlameGraphControl")
    void testStreamFlameGraphDelegates() {
        resource.streamFlameGraph(sink, sse, "CPU_TIME", "json");
        verify(flameGraphControl).registerSink(sink, sse, com.helix.profiler.flamegraph.MetricType.CPU_TIME, "json");
    }
}
