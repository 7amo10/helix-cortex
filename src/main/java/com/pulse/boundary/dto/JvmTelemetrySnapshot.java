package com.pulse.boundary.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * Real-time snapshot of JVM memory, garbage collection, and threading metrics.
 */
public record JvmTelemetrySnapshot(
        long heapUsedBytes,
        long heapMaxBytes,
        double heapUsedPercent,
        long gcCollectionCount,
        long gcCollectionTimeMs,
        int threadCount,
        int daemonThreadCount,
        long uptimeMs,
        @JsonSerialize(using = ToStringSerializer.class)
        Instant sampledAt
) {
    public JvmTelemetrySnapshot {
        if (sampledAt == null) {
            sampledAt = Instant.now();
        }
    }
}
