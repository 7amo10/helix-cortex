package com.pulse.boundary.dto;

import java.util.List;

/**
 * Acknowledgment response for batch stream ingestion.
 */
public record BatchStreamIngestResponse(
        int total,
        int accepted,
        List<StreamIngestResponse> results
) {
}
