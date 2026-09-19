package com.pulse.boundary.dto;

/**
 * Telemetry response representing L4 distributed Redis cache metrics.
 */
public record CacheMetricsResponse(
        long hitCount,
        long missCount,
        boolean connected,
        double hitRatio,
        boolean fallbackActive,
        String host,
        int port
) {
}
