package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise orchestrator managing immutable audit record persistence and periodic Merkle root checkpoint publication.
 *
 * <p>Provides fault-tolerant execution recording such that database or serialization errors
 * never disrupt active rule evaluation on the critical path.</p>
 */
@ApplicationScoped
public class AuditTrailService {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);

    @Inject
    private AuditRecordRepository auditRepository;

    @Inject
    private MerkleRootRepository merkleRootRepository;

    @Inject
    private AuditEventSerializer serializer;

    @Inject
    private MerkleChainAppender appender;

    @Inject
    private Ed25519Signer signer;

    private final AtomicLong sequencer = new AtomicLong(0);

    public AuditTrailService() {}

    public AuditTrailService(AuditRecordRepository auditRepository,
                             MerkleRootRepository merkleRootRepository,
                             AuditEventSerializer serializer,
                             MerkleChainAppender appender,
                             Ed25519Signer signer) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository cannot be null");
        this.merkleRootRepository = Objects.requireNonNull(merkleRootRepository, "merkleRootRepository cannot be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer cannot be null");
        this.appender = Objects.requireNonNull(appender, "appender cannot be null");
        this.signer = Objects.requireNonNull(signer, "signer cannot be null");
    }

    /**
     * Records a rule execution event, computes the canonical JSON, SHA-256 hash, and chains the leaf hash.
     * Fault-tolerant: errors are logged as errors without bubbling up to caller.
     *
     * @param ruleName        name of the executed rule
     * @param ruleVersion     version of the executed rule
     * @param tenantId        tenant identifier
     * @param executionResult execution status string (e.g. PASSED, FAILED)
     * @param durationNanos   execution latency in nanoseconds
     * @param isSandboxRun    whether rule was evaluated in sandbox
     * @return persisted AuditRecord, or null if an error occurred
     */
    public AuditRecord recordExecution(String ruleName, String ruleVersion, String tenantId,
                                       String executionResult, long durationNanos, boolean isSandboxRun) {
        try {
            long seq = sequencer.incrementAndGet();

            String prevHash = auditRepository.findLatest()
                    .map(AuditRecord::getLeafHash)
                    .orElse(null);

            String eventJson = serializer.toCanonicalJson(
                    ruleName, ruleVersion, tenantId, executionResult, durationNanos);
            String eventHash = serializer.sha256Hex(eventJson);
            String leafHash = appender.appendLeaf(prevHash, eventHash);

            AuditRecord record = AuditRecord.builder()
                    .recordSeq(seq)
                    .ruleName(ruleName)
                    .ruleVersion(ruleVersion)
                    .tenantId(tenantId)
                    .executionResult(executionResult)
                    .durationNanos(durationNanos)
                    .eventJson(eventJson)
                    .eventHash(eventHash)
                    .prevHash(prevHash)
                    .leafHash(leafHash)
                    .isSandboxRun(isSandboxRun)
                    .build();

            auditRepository.save(record);
            log.debug("Persisted audit record {} for rule '{}' (tenant: {}, result: {}, leaf: {})",
                    seq, ruleName, tenantId, executionResult, leafHash);
            return record;
        } catch (Exception e) {
            log.error("Failed to record audit event for rule '{}' (tenant: {}): {}",
                    ruleName, tenantId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Publishes a signed Merkle root checkpoint for the given inclusive sequence range.
     *
     * @param fromSeq lower bound sequence number
     * @param toSeq   upper bound sequence number
     * @return persisted MerkleRoot entity
     * @throws Exception if signing fails or target sequence is not found
     */
    public MerkleRoot publishMerkleRoot(long fromSeq, long toSeq) throws Exception {
        Optional<AuditRecord> targetRecord = auditRepository.findBySeq(toSeq);
        if (targetRecord.isEmpty()) {
            throw new IllegalArgumentException("No audit record found for toSeq=" + toSeq);
        }

        String rootHash = targetRecord.get().getLeafHash();
        byte[] signature = signer.sign(rootHash.getBytes());
        String sigB64 = Base64.getEncoder().encodeToString(signature);
        String pubKeyB64 = signer.getPublicKeyBase64();

        MerkleRoot root = MerkleRoot.builder()
                .rootHash(rootHash)
                .signatureB64(sigB64)
                .publicKeyB64(pubKeyB64)
                .fromSeq(fromSeq)
                .toSeq(toSeq)
                .build();

        merkleRootRepository.save(root);
        log.info("Published MerkleRoot checkpoint for range [{}-{}] with rootHash={}",
                fromSeq, toSeq, rootHash);
        return root;
    }

    public AuditRecordRepository getAuditRepository() {
        return auditRepository;
    }

    public MerkleRootRepository getMerkleRootRepository() {
        return merkleRootRepository;
    }
}
