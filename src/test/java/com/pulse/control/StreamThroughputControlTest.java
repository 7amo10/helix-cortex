package com.pulse.control;

import com.pulse.boundary.dto.StreamThroughputSnapshot;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Task: Stream Throughput Control Unit Tests")
class StreamThroughputControlTest {

    private StreamIngestionService ingestionService;
    private StreamConsumerCoordinator coordinator;
    private Sse sse;
    private ScheduledExecutorService scheduledExecutor;
    private SseBroadcaster broadcaster;
    private StreamThroughputControl control;

    @BeforeEach
    void setUp() {
        ingestionService = mock(StreamIngestionService.class);
        coordinator = mock(StreamConsumerCoordinator.class);
        sse = mock(Sse.class);
        scheduledExecutor = mock(ScheduledExecutorService.class);
        broadcaster = mock(SseBroadcaster.class);

        control = new StreamThroughputControl(ingestionService, coordinator, sse, scheduledExecutor, broadcaster);
    }

    @Test
    @DisplayName("Should compute accurate snapshot including queue depth and P99 latency")
    void testCurrentSnapshotComputation() {
        when(ingestionService.getTotalIngested()).thenReturn(1000L);
        when(coordinator.getTotalConsumed()).thenReturn(950L);
        when(coordinator.getTotalPersisted()).thenReturn(950L);

        // Record 100 sample latencies: 1ms to 100ms
        for (int i = 1; i <= 100; i++) {
            control.recordLatency(i);
        }

        StreamThroughputSnapshot snapshot = control.currentSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.queueDepth()).isEqualTo(50L); // 1000 ingested - 950 persisted = 50
        assertThat(snapshot.totalIngested()).isEqualTo(1000L);
        assertThat(snapshot.totalPersisted()).isEqualTo(950L);
        // P99 of 1..100 is 99.0
        assertThat(snapshot.p99LatencyMs()).isEqualTo(99.0);
        assertThat(snapshot.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("Should register SseEventSink and send initial snapshot immediately")
    void testRegisterSinkSendsInitialEvent() {
        SseEventSink sink = mock(SseEventSink.class);
        OutboundSseEvent.Builder eventBuilder = mock(OutboundSseEvent.Builder.class);
        OutboundSseEvent outboundEvent = mock(OutboundSseEvent.class);

        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(any())).thenReturn(eventBuilder);
        when(eventBuilder.id(any())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(MediaType.APPLICATION_JSON_TYPE)).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);

        control.registerSink(sink, sse);

        verify(broadcaster, times(1)).register(sink);
        verify(sink, times(1)).send(outboundEvent);
    }

    @Test
    @DisplayName("Should broadcast throughput snapshot to broadcaster")
    void testSampleAndBroadcast() {
        OutboundSseEvent.Builder eventBuilder = mock(OutboundSseEvent.Builder.class);
        OutboundSseEvent outboundEvent = mock(OutboundSseEvent.class);

        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(any())).thenReturn(eventBuilder);
        when(eventBuilder.id(any())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(MediaType.APPLICATION_JSON_TYPE)).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);

        control.sampleAndBroadcast();

        verify(broadcaster, times(1)).broadcast(outboundEvent);
    }
}
