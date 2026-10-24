-- Table: helix_audit_records
-- Immutable append-only audit trail capturing each rule execution event,
-- its canonical event payload JSON, SHA-256 event hash, and chained Merkle leaf hash.

CREATE TABLE IF NOT EXISTS helix_audit_records (
    id                  BIGSERIAL PRIMARY KEY,
    record_seq          BIGINT NOT NULL UNIQUE,
    rule_name           VARCHAR(255) NOT NULL,
    rule_version        VARCHAR(64) NOT NULL,
    tenant_id           VARCHAR(128) NOT NULL,
    execution_result    VARCHAR(16) NOT NULL,
    duration_nanos      BIGINT NOT NULL,
    event_json          TEXT NOT NULL,
    event_hash          CHAR(64) NOT NULL,
    prev_hash           CHAR(64),
    leaf_hash           CHAR(64) NOT NULL,
    evaluated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_sandbox_run      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_audit_rule ON helix_audit_records (rule_name, rule_version);
CREATE INDEX IF NOT EXISTS idx_audit_tenant ON helix_audit_records (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_evaluated_at ON helix_audit_records (evaluated_at DESC);
