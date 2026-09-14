package com.pulse.control;

import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

/**
 * Control bean collecting real-time JVM MXBean telemetry and broadcasting
 * SSE events to connected clients at a 1-second fixed frequency.
 */
@ApplicationScoped
public class TelemetryControl {

    private static final Logger log = LoggerFactory.getLogger(TelemetryControl.class);

    @Context
    private Sse sse;

    @Resource(lookup = "java:comp/DefaultManagedScheduledExecutorService")
    private ManagedScheduledExecutorService scheduledExecutor;

    private SseBroadcaster broadcaster;
    private ScheduledExecutorService localExecutor;
    private ScheduledFuture<?> broadcastTask;

    public TelemetryControl() {
    }

    public TelemetryControl(Sse sse, ScheduledExecutorService scheduledExecutor, SseBroadcaster broadcaster) {
        this.sse = sse;
        this.localExecutor = scheduledExecutor;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void init() {
        if (broadcaster == null && sse != null) {
            broadcaster = sse.newBroadcaster();
            broadcaster.onClose(sink -> log.info("SSE telemetry sink disconnected: {}", sink));
            broadcaster.onError((sink, throwable) ->
                    log.warn("SSE telemetry sink error on {}: {}", sink, throwable.getMessage()));
        }

        ScheduledExecutorService executorToUse = scheduledExecutor != null
                ? scheduledExecutor
                : (localExecutor != null ? localExecutor : Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "jvm-telemetry-sampler");
                    t.setDaemon(true);
                    return t;
                }));

        if (localExecutor == null && scheduledExecutor == null) {
            localExecutor = executorToUse;
        }

        try {
            broadcastTask = executorToUse.scheduleAtFixedRate(
                    this::sampleAndBroadcast,
                    0,
                    1,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Could not schedule telemetry sampler: {}", e.getMessage());
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
    }

    /**
     * Registers a connected client's SSE event sink with the broadcaster.
     *
     * @param sink client event sink
     */
    public void registerSink(SseEventSink sink) {
        if (sink == null) {
            return;
        }
        if (broadcaster == null && sse != null) {
            broadcaster = sse.newBroadcaster();
            broadcaster.onClose(s -> log.info("SSE telemetry sink disconnected: {}", s));
        }
        if (broadcaster != null) {
            broadcaster.register(sink);
        }
    }

    /**
     * Gathers a point-in-time telemetry snapshot directly from standard Java MXBeans.
     *
     * @return populated snapshot
     */
    public JvmTelemetrySnapshot currentSnapshot() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long heapUsed = heap.getUsed();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        double heapUsedPercent = heapMax > 0 ? (heapUsed * 100.0) / heapMax : 0.0;

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            if (count > 0) {
                gcCount += count;
            }
            long time = gc.getCollectionTime();
            if (time > 0) {
                gcTimeMs += time;
            }
        }

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();
        int daemonCount = threadBean.getDaemonThreadCount();

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = runtimeBean.getUptime();

        return new JvmTelemetrySnapshot(
                heapUsed,
                heapMax,
                heapUsedPercent,
                gcCount,
                gcTimeMs,
                threadCount,
                daemonCount,
                uptimeMs,
                Instant.now()
        );
    }

    /**
     * Samples the JVM metrics and broadcasts an SSE event to all registered listeners.
     */
    public void sampleAndBroadcast() {
        try {
            JvmTelemetrySnapshot snapshot = currentSnapshot();
            if (broadcaster != null && sse != null) {
                OutboundSseEvent event = sse.newEventBuilder()
                        .name("jvm-telemetry")
                        .id(String.valueOf(snapshot.sampledAt().toEpochMilli()))
                        .mediaType(MediaType.APPLICATION_JSON_TYPE)
                        .data(JvmTelemetrySnapshot.class, snapshot)
                        .build();
                broadcaster.broadcast(event);
            }
        } catch (Exception e) {
            log.warn("Failed to sample and broadcast telemetry event: {}", e.getMessage());
        }
    }

    public SseBroadcaster getBroadcaster() {
        return broadcaster;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }

    public void setBroadcaster(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }
}
