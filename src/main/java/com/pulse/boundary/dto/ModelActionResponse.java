package com.pulse.boundary.dto;

/**
 * Generic response payload for model lifecycle operations (activation, deletion).
 */
public record ModelActionResponse(
        String status,
        String modelName,
        String version,
        String message
) {}
