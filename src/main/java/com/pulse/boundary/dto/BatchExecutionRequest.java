package com.pulse.boundary.dto;

import java.util.List;
import java.util.Map;

/**
 * Request payload for high-concurrency batch rule evaluations across Project Loom virtual threads.
 */
public record BatchExecutionRequest(
        Long sessionId,
        List<Map<String, Object>> batch
) {
}
