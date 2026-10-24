package com.pulse.audit;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * JPA entity representing an immutable append-only audit trail record for a single rule execution.
 *
 * <p>Tracks monotonically increasing sequence numbers, tenant identity, execution outcome,
 * canonical event JSON, SHA-256 event hash, and chained Merkle leaf hash for tamper-evident verification.</p>
 */
@Entity
@Table(name = "helix_audit_records",
        indexes = {
                @Index(name = "idx_audit_rule", columnList = "rule_name, rule_version"),
                @Index(name = "idx_audit_tenant", columnList = "tenant_id"),
                @Index(name = "idx_audit_evaluated_at", columnList = "evaluated_at DESC")
        }
)
public class AuditRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "record_seq", nullable = false, unique = true)
    private Long recordSeq;

    @Column(name = "rule_name", nullable = false, length = 255)
    private String ruleName;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "execution_result", nullable = false, length = 16)
    private String executionResult;

    @Column(name = "duration_nanos", nullable = false)
    private long durationNanos;

    @Column(name = "event_json", nullable = false, columnDefinition = "TEXT")
    private String eventJson;

    @Column(name = "event_hash", nullable = false, length = 64)
    private String eventHash;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "leaf_hash", nullable = false, length = 64)
    private String leafHash;

    @Column(name = "evaluated_at", nullable = false)
    private OffsetDateTime evaluatedAt = OffsetDateTime.now();

    @Column(name = "is_sandbox_run", nullable = false)
    private boolean isSandboxRun;

    protected AuditRecord() {}

    private AuditRecord(Builder b) {
        this.recordSeq = b.recordSeq;
        this.ruleName = b.ruleName;
        this.ruleVersion = b.ruleVersion;
        this.tenantId = b.tenantId;
        this.executionResult = b.executionResult;
        this.durationNanos = b.durationNanos;
        this.eventJson = b.eventJson;
        this.eventHash = b.eventHash;
        this.prevHash = b.prevHash;
        this.leafHash = b.leafHash;
        this.isSandboxRun = b.isSandboxRun;
        this.evaluatedAt = b.evaluatedAt != null ? b.evaluatedAt : OffsetDateTime.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long recordSeq;
        private String ruleName;
        private String ruleVersion;
        private String tenantId;
        private String executionResult;
        private long durationNanos;
        private String eventJson;
        private String eventHash;
        private String prevHash;
        private String leafHash;
        private boolean isSandboxRun;
        private OffsetDateTime evaluatedAt;

        public Builder recordSeq(long v) {
            this.recordSeq = v;
            return this;
        }

        public Builder ruleName(String v) {
            this.ruleName = v;
            return this;
        }

        public Builder ruleVersion(String v) {
            this.ruleVersion = v;
            return this;
        }

        public Builder tenantId(String v) {
            this.tenantId = v;
            return this;
        }

        public Builder executionResult(String v) {
            this.executionResult = v;
            return this;
        }

        public Builder durationNanos(long v) {
            this.durationNanos = v;
            return this;
        }

        public Builder eventJson(String v) {
            this.eventJson = v;
            return this;
        }

        public Builder eventHash(String v) {
            this.eventHash = v;
            return this;
        }

        public Builder prevHash(String v) {
            this.prevHash = v;
            return this;
        }

        public Builder leafHash(String v) {
            this.leafHash = v;
            return this;
        }

        public Builder isSandboxRun(boolean v) {
            this.isSandboxRun = v;
            return this;
        }

        public Builder evaluatedAt(OffsetDateTime v) {
            this.evaluatedAt = v;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(this);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecordSeq() {
        return recordSeq;
    }

    public void setRecordSeq(Long recordSeq) {
        this.recordSeq = recordSeq;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public void setDurationNanos(long durationNanos) {
        this.durationNanos = durationNanos;
    }

    public String getEventJson() {
        return eventJson;
    }

    public void setEventJson(String eventJson) {
        this.eventJson = eventJson;
    }

    public String getEventHash() {
        return eventHash;
    }

    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getLeafHash() {
        return leafHash;
    }

    public void setLeafHash(String leafHash) {
        this.leafHash = leafHash;
    }

    public OffsetDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(OffsetDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public boolean isSandboxRun() {
        return isSandboxRun;
    }

    public void setSandboxRun(boolean sandboxRun) {
        isSandboxRun = sandboxRun;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditRecord that = (AuditRecord) o;
        return Objects.equals(recordSeq, that.recordSeq);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordSeq);
    }

    @Override
    public String toString() {
        return "AuditRecord{" +
                "id=" + id +
                ", recordSeq=" + recordSeq +
                ", ruleName='" + ruleName + '\'' +
                ", ruleVersion='" + ruleVersion + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", executionResult='" + executionResult + '\'' +
                ", durationNanos=" + durationNanos +
                ", leafHash='" + leafHash + '\'' +
                ", isSandboxRun=" + isSandboxRun +
                ", evaluatedAt=" + evaluatedAt +
                '}';
    }
}
