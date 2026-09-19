package com.pulse.boundary.dto;

/**
 * Response payload for cache invalidation operations.
 */
public record CacheInvalidateResponse(
        String status,
        String message,
        String ruleName
) {
}
