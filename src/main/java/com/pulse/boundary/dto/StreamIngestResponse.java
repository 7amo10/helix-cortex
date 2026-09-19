package com.pulse.boundary.dto;

/**
 * Immediate acknowledgment response returned by the streaming ingestion gateway.
 */
public record StreamIngestResponse(
        String eventId,
        String topic,
        String ruleName,
        String status,
        long timestamp
) {
}
