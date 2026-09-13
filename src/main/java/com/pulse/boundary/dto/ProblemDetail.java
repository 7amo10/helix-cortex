package com.pulse.boundary.dto;

/**
 * RFC 7807 problem details representation for API error payloads.
 */
public record ProblemDetail(
        int status,
        String title,
        String detail
) {
}
