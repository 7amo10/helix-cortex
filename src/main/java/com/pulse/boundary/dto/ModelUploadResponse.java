package com.pulse.boundary.dto;

/**
 * Response payload returned upon successful model upload and registration.
 */
public record ModelUploadResponse(
        String modelName,
        String version,
        String filePath,
        int fileSizeKb,
        boolean active,
        String location
) {}
