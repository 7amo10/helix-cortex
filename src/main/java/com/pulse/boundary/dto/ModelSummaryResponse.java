package com.pulse.boundary.dto;

/**
 * Summary DTO representing a registered ML model family in the catalog.
 */
public record ModelSummaryResponse(
        String modelName,
        String activeVersion,
        int versionCount,
        String outputType,
        int activeFileSizeKb
) {}
