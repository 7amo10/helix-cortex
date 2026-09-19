package com.pulse.boundary.dto;

/**
 * Request payload for distributed cache invalidation.
 */
public record CacheInvalidateRequest(
        String ruleName,
        String version
) {
    public CacheInvalidateRequest(String ruleName) {
        this(ruleName, null);
    }
}
