-- Table: helix_merkle_roots
-- Periodic cryptographic checkpoints publishing the computed Merkle root hash,
-- sequence bounds, and Ed25519 asymmetric digital signature with public verification key.

CREATE TABLE IF NOT EXISTS helix_merkle_roots (
    id              BIGSERIAL PRIMARY KEY,
    root_hash       CHAR(64) NOT NULL,
    signature_b64   TEXT NOT NULL,
    public_key_b64  TEXT NOT NULL,
    from_seq        BIGINT NOT NULL,
    to_seq          BIGINT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_merkle_roots_seq ON helix_merkle_roots (from_seq, to_seq);
CREATE INDEX IF NOT EXISTS idx_merkle_roots_published ON helix_merkle_roots (published_at DESC);
