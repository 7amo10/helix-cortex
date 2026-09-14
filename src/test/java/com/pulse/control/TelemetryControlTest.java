package com.pulse.control;

import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryControlTest {

    @Mock
    private Sse sse;

    @Mock
    private SseBroadcaster broadcaster;

    @Mock
    private SseEventSink sink;

    @Mock
    private OutboundSseEvent.Builder eventBuilder;

    @Mock
    private OutboundSseEvent outboundEvent;

    @Mock
    private ScheduledExecutorService scheduledExecutor;

    private TelemetryControl control;

    @BeforeEach
    void setUp() {
        control = new TelemetryControl(sse, scheduledExecutor, broadcaster);
    }

    @AfterEach
    void tearDown() {
        control.destroy();
    }

    @Test
    @DisplayName("currentSnapshot reads real JVM MXBeans and produces valid metrics")
    void testCurrentSnapshotRealJvm() {
        JvmTelemetrySnapshot snapshot = control.currentSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.heapUsedBytes()).isGreaterThan(0L);
        assertThat(snapshot.heapMaxBytes()).isGreaterThan(0L);

        double expectedPercent = (snapshot.heapUsedBytes() * 100.0) / snapshot.heapMaxBytes();
        assertThat(snapshot.heapUsedPercent()).isCloseTo(expectedPercent, within(0.01));

        assertThat(snapshot.gcCollectionCount()).isGreaterThanOrEqualTo(0L);
        assertThat(snapshot.gcCollectionTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(snapshot.threadCount()).isGreaterThan(0);
        assertThat(snapshot.daemonThreadCount()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.uptimeMs()).isGreaterThan(0L);

        assertThat(Duration.between(snapshot.sampledAt(), Instant.now()).abs().getSeconds()).isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("registerSink delegates to broadcaster")
    void testRegisterSink() {
        control.registerSink(sink);
        verify(broadcaster).register(sink);
    }

    @Test
    @DisplayName("sampleAndBroadcast creates SSE event and broadcasts")
    void testSampleAndBroadcast() {
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.id(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(any(MediaType.class))).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);

        control.sampleAndBroadcast();

        verify(sse).newEventBuilder();
        verify(eventBuilder).name("jvm-telemetry");
        verify(eventBuilder).mediaType(MediaType.APPLICATION_JSON_TYPE);
        verify(eventBuilder).data(eq(JvmTelemetrySnapshot.class), any(JvmTelemetrySnapshot.class));
        verify(broadcaster).broadcast(outboundEvent);
    }

    @Test
    @DisplayName("init creates broadcaster if needed and schedules background sampler")
    void testInit() {
        when(sse.newBroadcaster()).thenReturn(broadcaster);
        TelemetryControl freshControl = new TelemetryControl(sse, scheduledExecutor, null);

        freshControl.init();

        verify(sse).newBroadcaster();
        verify(scheduledExecutor).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(1L), any());
        freshControl.destroy();
    }
}
