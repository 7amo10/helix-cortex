package com.pulse.boundary.dto;

/**
 * Observability snapshot for stream ingestion and persistence coordination.
 */
public record StreamCoordinatorStats(
        long totalIngested,
        long totalConsumed,
        long totalPersisted,
        long batchesPersisted,
        boolean running,
        String inputTopic,
        String resultsTopic
) {
}
