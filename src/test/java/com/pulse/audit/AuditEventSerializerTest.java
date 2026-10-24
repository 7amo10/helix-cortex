package com.pulse.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditEventSerializer Tests")
class AuditEventSerializerTest {

    private final AuditEventSerializer serializer = new AuditEventSerializer();

    @Test
    @DisplayName("toCanonicalJson creates deterministic JSON with escaped fields")
    void testToCanonicalJson() {
        Instant fixedInstant = Instant.parse("2026-10-01T12:00:00Z");
        String json = serializer.toCanonicalJson(
                "Risk\"Rule",
                "1.0.0",
                "tenant\\alpha",
                "PASSED",
                125_000L,
                fixedInstant
        );

        assertNotNull(json);
        assertTrue(json.contains("\"ruleName\":\"Risk\\\"Rule\""));
        assertTrue(json.contains("\"ruleVersion\":\"1.0.0\""));
        assertTrue(json.contains("\"tenantId\":\"tenant\\\\alpha\""));
        assertTrue(json.contains("\"result\":\"PASSED\""));
        assertTrue(json.contains("\"durationNanos\":125000"));
        assertTrue(json.contains("\"timestamp\":\"2026-10-01T12:00:00Z\""));
    }

    @Test
    @DisplayName("sha256Hex produces 64-char lowercase hexadecimal hash")
    void testSha256Hex() {
        String hash = serializer.sha256Hex("hello world");
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }
}
