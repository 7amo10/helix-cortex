package com.pulse.boundary.dto;

import java.util.List;

/**
 * Batch request payload for rapid multi-event streaming ingestion.
 */
public record BatchStreamIngestRequest(
        List<StreamIngestRequest> events
) {
    public BatchStreamIngestRequest {
        if (events == null) {
            events = List.of();
        }
    }
}
