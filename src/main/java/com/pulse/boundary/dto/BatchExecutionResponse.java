package com.pulse.boundary.dto;

import com.pulse.entity.OpcodeMetric;

import java.util.List;

/**
 * Response payload returning aggregated telemetry and metrics for batch evaluations.
 */
public record BatchExecutionResponse(
        Long sessionId,
        int totalEvaluated,
        long totalExecutionTimeNanos,
        List<OpcodeMetric> metrics
) {
}
