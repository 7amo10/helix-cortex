package com.pulse.boundary.dto;

import java.util.Map;

/**
 * Request payload for rule compilation.
 */
public record RuleRequest(
        String ruleName,
        String ruleVersion,
        String expression,
        Map<String, String> inputSchema
) {
}
