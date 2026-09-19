package com.pulse.boundary.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * Real-time streaming throughput telemetry snapshot broadcasting
 * events per second, queue depth, P99 execution latency, and cumulative metrics.
 */
public record StreamThroughputSnapshot(
        double eventsPerSecond,
        long queueDepth,
        double p99LatencyMs,
        long totalIngested,
        long totalConsumed,
        long totalPersisted,
        @JsonSerialize(using = ToStringSerializer.class)
        Instant timestamp
) {
    public StreamThroughputSnapshot(double eventsPerSecond, long queueDepth, double p99LatencyMs,
                                    long totalIngested, long totalConsumed, long totalPersisted) {
        this(eventsPerSecond, queueDepth, p99LatencyMs, totalIngested, totalConsumed, totalPersisted, Instant.now());
    }
}
