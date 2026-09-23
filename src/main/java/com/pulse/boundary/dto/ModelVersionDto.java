package com.pulse.boundary.dto;

/**
 * Detailed representation of a specific model version artifact.
 */
public record ModelVersionDto(
        String version,
        String filePath,
        int fileSizeKb,
        String inputSchema,
        String outputType,
        boolean active,
        String uploadedBy,
        String uploadedAt,
        String description
) {}
