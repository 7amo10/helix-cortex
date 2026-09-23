package com.pulse.boundary.dto;

import java.util.List;

/**
 * Detailed DTO representing a model family, its active version, and version history.
 */
public record ModelDetailResponse(
        String modelName,
        String activeVersion,
        int versionCount,
        List<ModelVersionDto> versions
) {}
