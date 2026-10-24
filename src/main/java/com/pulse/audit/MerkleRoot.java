package com.pulse.audit;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * JPA entity representing a published Merkle root checkpoint for a continuous sequence of audit records.
 *
 * <p>Stores the cryptographic Merkle root hash, inclusive sequence range bounds,
 * and the Ed25519 digital signature with Base64-encoded public verification key.</p>
 */
@Entity
@Table(name = "helix_merkle_roots",
        indexes = {
                @Index(name = "idx_merkle_roots_seq", columnList = "from_seq, to_seq"),
                @Index(name = "idx_merkle_roots_published", columnList = "published_at DESC")
        }
)
public class MerkleRoot implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "root_hash", nullable = false, length = 64)
    private String rootHash;

    @Column(name = "signature_b64", nullable = false, columnDefinition = "TEXT")
    private String signatureB64;

    @Column(name = "public_key_b64", nullable = false, columnDefinition = "TEXT")
    private String publicKeyB64;

    @Column(name = "from_seq", nullable = false)
    private long fromSeq;

    @Column(name = "to_seq", nullable = false)
    private long toSeq;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt = OffsetDateTime.now();

    protected MerkleRoot() {}

    private MerkleRoot(Builder b) {
        this.rootHash = b.rootHash;
        this.signatureB64 = b.signatureB64;
        this.publicKeyB64 = b.publicKeyB64;
        this.fromSeq = b.fromSeq;
        this.toSeq = b.toSeq;
        this.publishedAt = b.publishedAt != null ? b.publishedAt : OffsetDateTime.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String rootHash;
        private String signatureB64;
        private String publicKeyB64;
        private long fromSeq;
        private long toSeq;
        private OffsetDateTime publishedAt;

        public Builder rootHash(String v) {
            this.rootHash = v;
            return this;
        }

        public Builder signatureB64(String v) {
            this.signatureB64 = v;
            return this;
        }

        public Builder publicKeyB64(String v) {
            this.publicKeyB64 = v;
            return this;
        }

        public Builder fromSeq(long v) {
            this.fromSeq = v;
            return this;
        }

        public Builder toSeq(long v) {
            this.toSeq = v;
            return this;
        }

        public Builder publishedAt(OffsetDateTime v) {
            this.publishedAt = v;
            return this;
        }

        public MerkleRoot build() {
            return new MerkleRoot(this);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRootHash() {
        return rootHash;
    }

    public void setRootHash(String rootHash) {
        this.rootHash = rootHash;
    }

    public String getSignatureB64() {
        return signatureB64;
    }

    public void setSignatureB64(String signatureB64) {
        this.signatureB64 = signatureB64;
    }

    public String getPublicKeyB64() {
        return publicKeyB64;
    }

    public void setPublicKeyB64(String publicKeyB64) {
        this.publicKeyB64 = publicKeyB64;
    }

    public long getFromSeq() {
        return fromSeq;
    }

    public void setFromSeq(long fromSeq) {
        this.fromSeq = fromSeq;
    }

    public long getToSeq() {
        return toSeq;
    }

    public void setToSeq(long toSeq) {
        this.toSeq = toSeq;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerkleRoot that = (MerkleRoot) o;
        return Objects.equals(rootHash, that.rootHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootHash);
    }

    @Override
    public String toString() {
        return "MerkleRoot{" +
                "id=" + id +
                ", rootHash='" + rootHash + '\'' +
                ", fromSeq=" + fromSeq +
                ", toSeq=" + toSeq +
                ", publishedAt=" + publishedAt +
                '}';
    }
}
