package com.pulse.boundary.dto;

import java.util.Map;

/**
 * Request payload for streaming event ingestion via REST gateway.
 */
public record StreamIngestRequest(
        String eventId,
        String topic,
        String ruleName,
        Map<String, Object> variables,
        Map<String, String> headers
) {
    public StreamIngestRequest(String ruleName, Map<String, Object> variables) {
        this(null, null, ruleName, variables, null);
    }

    public StreamIngestRequest(String topic, String ruleName, Map<String, Object> variables) {
        this(null, topic, ruleName, variables, null);
    }
}
