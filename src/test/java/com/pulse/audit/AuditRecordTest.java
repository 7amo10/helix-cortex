package com.pulse.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditRecord and MerkleRoot JPA Entity Unit Tests")
class AuditRecordTest {

    @Test
    @DisplayName("AuditRecord builder constructs entity with valid attributes and defaults")
    void testAuditRecordBuildsCorrectly() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        AuditRecord record = AuditRecord.builder()
                .recordSeq(100L)
                .ruleName("FraudVelocityRule")
                .ruleVersion("1.0.0")
                .tenantId("tenant-clearPay")
                .executionResult("PASSED")
                .durationNanos(412_000L)
                .eventJson("{\"rule\":\"FraudVelocityRule\"}")
                .eventHash("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
                .prevHash(null)
                .leafHash("bbbbef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
                .isSandboxRun(false)
                .build();

        assertNotNull(record);
        assertEquals(100L, record.getRecordSeq());
        assertEquals("FraudVelocityRule", record.getRuleName());
        assertEquals("1.0.0", record.getRuleVersion());
        assertEquals("tenant-clearPay", record.getTenantId());
        assertEquals("PASSED", record.getExecutionResult());
        assertEquals(412_000L, record.getDurationNanos());
        assertEquals("{\"rule\":\"FraudVelocityRule\"}", record.getEventJson());
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", record.getEventHash());
        assertNull(record.getPrevHash());
        assertEquals("bbbbef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", record.getLeafHash());
        assertFalse(record.isSandboxRun());
        assertNotNull(record.getEvaluatedAt());
        assertTrue(record.getEvaluatedAt().isAfter(before) || record.getEvaluatedAt().isEqual(before));
    }

    @Test
    @DisplayName("AuditRecord equality and hashCode adhere to recordSeq natural key")
    void testAuditRecordEqualityAndHashCode() {
        AuditRecord record1 = AuditRecord.builder()
                .recordSeq(42L)
                .ruleName("RuleA")
                .ruleVersion("1.0")
                .tenantId("tenant-1")
                .executionResult("PASSED")
                .durationNanos(100L)
                .eventJson("{}")
                .eventHash("hash1")
                .leafHash("leaf1")
                .build();

        AuditRecord record2 = AuditRecord.builder()
                .recordSeq(42L)
                .ruleName("RuleB")
                .ruleVersion("2.0")
                .tenantId("tenant-2")
                .executionResult("FAILED")
                .durationNanos(200L)
                .eventJson("{}")
                .eventHash("hash2")
                .leafHash("leaf2")
                .build();

        AuditRecord record3 = AuditRecord.builder()
                .recordSeq(43L)
                .ruleName("RuleA")
                .ruleVersion("1.0")
                .tenantId("tenant-1")
                .executionResult("PASSED")
                .durationNanos(100L)
                .eventJson("{}")
                .eventHash("hash1")
                .leafHash("leaf1")
                .build();

        assertEquals(record1, record2);
        assertEquals(record1.hashCode(), record2.hashCode());
        assertNotEquals(record1, record3);
    }

    @Test
    @DisplayName("MerkleRoot builder constructs entity with valid attributes and bounds")
    void testMerkleRootBuildsCorrectly() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        MerkleRoot root = MerkleRoot.builder()
                .rootHash("ccccef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
                .signatureB64("base64signature==")
                .publicKeyB64("base64publickey==")
                .fromSeq(1L)
                .toSeq(1000L)
                .build();

        assertNotNull(root);
        assertEquals("ccccef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", root.getRootHash());
        assertEquals("base64signature==", root.getSignatureB64());
        assertEquals("base64publickey==", root.getPublicKeyB64());
        assertEquals(1L, root.getFromSeq());
        assertEquals(1000L, root.getToSeq());
        assertNotNull(root.getPublishedAt());
        assertTrue(root.getPublishedAt().isAfter(before) || root.getPublishedAt().isEqual(before));
    }

    @Test
    @DisplayName("MerkleRoot equality and hashCode adhere to rootHash natural key")
    void testMerkleRootEqualityAndHashCode() {
        MerkleRoot root1 = MerkleRoot.builder()
                .rootHash("hash123")
                .signatureB64("sig1")
                .publicKeyB64("key1")
                .fromSeq(1L)
                .toSeq(10L)
                .build();

        MerkleRoot root2 = MerkleRoot.builder()
                .rootHash("hash123")
                .signatureB64("sig2")
                .publicKeyB64("key2")
                .fromSeq(11L)
                .toSeq(20L)
                .build();

        MerkleRoot root3 = MerkleRoot.builder()
                .rootHash("hash456")
                .signatureB64("sig1")
                .publicKeyB64("key1")
                .fromSeq(1L)
                .toSeq(10L)
                .build();

        assertEquals(root1, root2);
        assertEquals(root1.hashCode(), root2.hashCode());
        assertNotEquals(root1, root3);
    }
}
