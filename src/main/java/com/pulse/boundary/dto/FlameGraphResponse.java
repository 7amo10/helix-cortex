package com.pulse.boundary.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST response payload encapsulating folded stack telemetry, d3-flame-graph hierarchy,
 * and Speedscope compatible JSON specifications.
 */
public record FlameGraphResponse(
        String metric,
        String unit,
        long totalSamples,
        int distinctStacks,
        int maxDepth,
        List<String> folded,
        FlameGraphNodeDto tree,
        Map<String, Object> speedscope,
        Instant timestamp
) {
}
