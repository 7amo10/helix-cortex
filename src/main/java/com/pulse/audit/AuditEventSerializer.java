package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Service providing canonical JSON serialization and SHA-256 cryptographic hashing for execution audit events.
 *
 * <p>Ensures deterministic byte representation of audit payloads across nodes and architectures
 * prior to cryptographic Merkle chaining and indexing.</p>
 */
@ApplicationScoped
public class AuditEventSerializer {

    private static final String SHA_256 = "SHA-256";

    /**
     * Serializes execution event attributes to canonical JSON format with the current timestamp.
     *
     * @param ruleName        name of the executed rule
     * @param ruleVersion     version of the executed rule
     * @param tenantId        tenant identifier
     * @param executionResult outcome status (e.g. PASSED, FAILED, ERROR)
     * @param durationNanos   execution duration in nanoseconds
     * @return canonical JSON payload string
     */
    public String toCanonicalJson(String ruleName, String ruleVersion, String tenantId,
                                  String executionResult, long durationNanos) {
        return toCanonicalJson(ruleName, ruleVersion, tenantId, executionResult, durationNanos, Instant.now());
    }

    /**
     * Serializes execution event attributes to canonical JSON format with an explicit timestamp.
     *
     * @param ruleName        name of the executed rule
     * @param ruleVersion     version of the executed rule
     * @param tenantId        tenant identifier
     * @param executionResult outcome status (e.g. PASSED, FAILED, ERROR)
     * @param durationNanos   execution duration in nanoseconds
     * @param timestamp       event timestamp
     * @return canonical JSON payload string
     */
    public String toCanonicalJson(String ruleName, String ruleVersion, String tenantId,
                                  String executionResult, long durationNanos, Instant timestamp) {
        return String.format(
                "{\"durationNanos\":%d,\"result\":\"%s\",\"ruleName\":\"%s\",\"ruleVersion\":\"%s\",\"tenantId\":\"%s\",\"timestamp\":\"%s\"}",
                durationNanos,
                escape(executionResult),
                escape(ruleName),
                escape(ruleVersion),
                escape(tenantId),
                timestamp != null ? timestamp.toString() : Instant.now().toString()
        );
    }

    /**
     * Computes the lowercase 64-character hexadecimal SHA-256 digest of the given input string.
     *
     * @param input UTF-8 input string
     * @return 64-character lowercase hexadecimal hash
     */
    public String sha256Hex(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
