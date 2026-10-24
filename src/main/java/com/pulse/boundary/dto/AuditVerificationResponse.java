package com.pulse.boundary.dto;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Detailed cryptographic integrity verification response for an audit record.
 *
 * @param recordSeq          sequence number of the record
 * @param ruleName           rule name
 * @param ruleVersion        rule version
 * @param tenantId           tenant identifier
 * @param executionResult    outcome of execution
 * @param durationNanos      execution duration
 * @param eventHash          SHA-256 event hash
 * @param prevHash           chained previous leaf hash (or 'GENESIS' if first)
 * @param leafHash           recalculated or stored chained leaf hash
 * @param evaluatedAt        record evaluation timestamp
 * @param isSandboxRun       whether execution was sandboxed
 * @param verified           true if cryptographic chaining and leaf match
 * @param computedLeafHash   leaf hash recalculated from prevHash and eventHash
 * @param integrityStatus    status message (e.g. VALID, TAMPERED)
 */
public record AuditVerificationResponse(
        long recordSeq,
        String ruleName,
        String ruleVersion,
        String tenantId,
        String executionResult,
        long durationNanos,
        String eventHash,
        String prevHash,
        String leafHash,
        OffsetDateTime evaluatedAt,
        boolean isSandboxRun,
        boolean verified,
        String computedLeafHash,
        String integrityStatus
) implements Serializable {}
