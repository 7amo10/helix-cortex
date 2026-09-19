package com.pulse.control;

import com.pulse.boundary.dto.StreamThroughputSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Control bean aggregating streaming ingestion and persistence metrics,
 * calculating real-time throughput (events/sec), queue depth, and P99 latency,
 * and broadcasting Server-Sent Events (SSE) to connected clients every 1,000 ms.
 */
@ApplicationScoped
public class StreamThroughputControl {

    private static final Logger log = LoggerFactory.getLogger(StreamThroughputControl.class);
    private static final int MAX_LATENCY_SAMPLES = 5000;

    @Inject
    private StreamIngestionService ingestionService;

    @Inject
    private StreamConsumerCoordinator coordinator;

    @Context
    private Sse sse;

    @Resource(lookup = "java:comp/DefaultManagedScheduledExecutorService")
    private ManagedScheduledExecutorService scheduledExecutor;

    private SseBroadcaster broadcaster;
    private ScheduledExecutorService localExecutor;
    private ScheduledFuture<?> broadcastTask;

    private final Queue<Double> latencyReservoir = new ConcurrentLinkedQueue<>();
    private final AtomicLong previousProcessedCount = new AtomicLong(0);
    private long previousTimestampNanos = System.nanoTime();

    public StreamThroughputControl() {
    }

    public StreamThroughputControl(StreamIngestionService ingestionService,
                                   StreamConsumerCoordinator coordinator,
                                   Sse sse,
                                   ScheduledExecutorService scheduledExecutor,
                                   SseBroadcaster broadcaster) {
        this.ingestionService = ingestionService;
        this.coordinator = coordinator;
        this.sse = sse;
        this.localExecutor = scheduledExecutor;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void init() {
        if (broadcaster == null && sse != null) {
            broadcaster = sse.newBroadcaster();
            broadcaster.onClose(sink -> log.info("SSE throughput sink disconnected: {}", sink));
            broadcaster.onError((sink, err) ->
                    log.warn("SSE throughput sink error on {}: {}", sink, err.getMessage()));
        }

        ScheduledExecutorService executorToUse = scheduledExecutor != null
                ? scheduledExecutor
                : (localExecutor != null ? localExecutor : Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "stream-throughput-sampler");
                    t.setDaemon(true);
                    return t;
                }));

        if (localExecutor == null && scheduledExecutor == null) {
            localExecutor = executorToUse;
        }

        try {
            previousTimestampNanos = System.nanoTime();
            previousProcessedCount.set(getCurrentTotalProcessed());
            broadcastTask = executorToUse.scheduleAtFixedRate(
                    this::sampleAndBroadcast,
                    1000,
                    1000,
                    TimeUnit.MILLISECONDS
            );
            log.info("StreamThroughputControl started broadcasting SSE telemetry every 1,000 ms");
        } catch (Exception e) {
            log.warn("Could not schedule stream throughput telemetry broadcaster: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (broadcastTask != null) {
            broadcastTask.cancel(true);
        }
        if (broadcaster != null) {
            try {
                broadcaster.close();
            } catch (Exception e) {
                log.debug("Error closing SseBroadcaster: {}", e.getMessage());
            }
        }
        if (localExecutor != null) {
            localExecutor.shutdownNow();
        }
        log.info("StreamThroughputControl shut down cleanly");
    }

    /**
     * Registers a client SSE event sink with the throughput broadcaster.
     *
     * @param sink client event sink
     * @param sseContext JAX-RS SSE context
     */
    public void registerSink(SseEventSink sink, Sse sseContext) {
        if (sink == null) {
            return;
        }
        if (this.sse == null && sseContext != null) {
            this.sse = sseContext;
        }
        if (broadcaster == null && this.sse != null) {
            broadcaster = this.sse.newBroadcaster();
            broadcaster.onClose(s -> log.info("SSE throughput sink disconnected: {}", s));
            broadcaster.onError((s, err) -> log.warn("SSE throughput sink error: {}", err.getMessage()));
        }
        if (broadcaster != null) {
            broadcaster.register(sink);
            // Send initial snapshot immediately upon connection
            try {
                StreamThroughputSnapshot snapshot = currentSnapshot();
                OutboundSseEvent event = this.sse.newEventBuilder()
                        .name("throughput")
                        .id(String.valueOf(snapshot.timestamp().toEpochMilli()))
                        .mediaType(MediaType.APPLICATION_JSON_TYPE)
                        .data(StreamThroughputSnapshot.class, snapshot)
                        .build();
                sink.send(event);
            } catch (Exception e) {
                log.warn("Could not send initial SSE throughput event: {}", e.getMessage());
            }
        }
    }

    /**
     * Records an execution latency sample (in milliseconds).
     *
     * @param latencyMs latency in milliseconds
     */
    public void recordLatency(double latencyMs) {
        if (latencyReservoir.size() >= MAX_LATENCY_SAMPLES) {
            latencyReservoir.poll();
        }
        latencyReservoir.offer(latencyMs);
    }

    /**
     * Records execution latency given nanoseconds.
     *
     * @param latencyNanos duration in nanoseconds
     */
    public void recordLatencyNanos(long latencyNanos) {
        recordLatency(latencyNanos / 1_000_000.0);
    }

    /**
     * Gathers a point-in-time snapshot of stream throughput, queue depth, and P99 latency.
     *
     * @return current telemetry snapshot
     */
    public StreamThroughputSnapshot currentSnapshot() {
        long currentNanos = System.nanoTime();
        long currentCount = getCurrentTotalProcessed();

        long prevCount = previousProcessedCount.getAndSet(currentCount);
        long elapsedNanos = currentNanos - previousTimestampNanos;
        previousTimestampNanos = currentNanos;

        double elapsedSeconds = elapsedNanos > 0 ? elapsedNanos / 1_000_000_000.0 : 1.0;
        long delta = Math.max(0, currentCount - prevCount);
        double eventsPerSecond = elapsedSeconds > 0 ? delta / elapsedSeconds : 0.0;

        long totalIngested = ingestionService != null ? ingestionService.getTotalIngested() : 0L;
        long totalConsumed = coordinator != null ? coordinator.getTotalConsumed() : 0L;
        long totalPersisted = coordinator != null ? coordinator.getTotalPersisted() : 0L;
        long queueDepth = Math.max(0, totalIngested - totalPersisted);

        double p99LatencyMs = calculateP99Latency();

        return new StreamThroughputSnapshot(
                roundTwoDecimals(eventsPerSecond),
                queueDepth,
                roundTwoDecimals(p99LatencyMs),
                totalIngested,
                totalConsumed,
                totalPersisted,
                Instant.now()
        );
    }

    /**
     * Samples metrics and broadcasts an SSE event to all connected listeners.
     */
    public void sampleAndBroadcast() {
        try {
            StreamThroughputSnapshot snapshot = currentSnapshot();
            if (broadcaster != null && sse != null) {
                OutboundSseEvent event = sse.newEventBuilder()
                        .name("throughput")
                        .id(String.valueOf(snapshot.timestamp().toEpochMilli()))
                        .mediaType(MediaType.APPLICATION_JSON_TYPE)
                        .data(StreamThroughputSnapshot.class, snapshot)
                        .build();
                broadcaster.broadcast(event);
            }
        } catch (Exception e) {
            log.warn("Failed to sample and broadcast stream throughput event: {}", e.getMessage());
        }
    }

    private double calculateP99Latency() {
        if (latencyReservoir.isEmpty()) {
            return 0.0;
        }

        List<Double> samples = new ArrayList<>(latencyReservoir);
        if (samples.isEmpty()) {
            return 0.0;
        }

        Collections.sort(samples);
        int index = (int) Math.ceil(0.99 * samples.size()) - 1;
        index = Math.max(0, Math.min(index, samples.size() - 1));
        return samples.get(index);
    }

    private long getCurrentTotalProcessed() {
        if (coordinator != null && coordinator.getTotalPersisted() > 0) {
            return coordinator.getTotalPersisted();
        }
        if (ingestionService != null) {
            return ingestionService.getTotalIngested();
        }
        return 0L;
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public SseBroadcaster getBroadcaster() {
        return broadcaster;
    }

    public void setBroadcaster(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }

    public void setIngestionService(StreamIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void setCoordinator(StreamConsumerCoordinator coordinator) {
        this.coordinator = coordinator;
    }
}
