package com.pulse.control;

import java.time.Instant;

/**
 * CDI event payload carrying antipattern detection information from JAR bytecode inspection.
 */
public record RuleAntipatternEvent(
        String className,
        String antipatternType,
        Instant detectedAt,
        Long jarAnalysisId
) {
    public RuleAntipatternEvent {
        if (detectedAt == null) {
            detectedAt = Instant.now();
        }
    }
}
