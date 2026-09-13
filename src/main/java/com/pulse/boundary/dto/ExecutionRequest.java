package com.pulse.boundary.dto;

import java.util.Map;

/**
 * Request payload for rule execution with dynamic variable context.
 */
public record ExecutionRequest(
        Map<String, Object> variables
) {
}
