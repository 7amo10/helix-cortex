package com.pulse.boundary.dto;

import java.util.Map;

/**
 * Request payload for rule execution with dynamic variable context.
 * Supports optional sessionId for direct endpoint POST /api/v1/rules/execute and async flag.
 */
public record ExecutionRequest(
        Long sessionId,
        Map<String, Object> variables,
        Boolean async
) {
    public ExecutionRequest(Map<String, Object> variables) {
        this(null, variables, false);
    }

    public ExecutionRequest(Long sessionId, Map<String, Object> variables) {
        this(sessionId, variables, false);
    }
}
