package com.pulse.boundary.dto;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Response payload representing a published and cryptographically signed Merkle root checkpoint.
 *
 * @param rootHash     SHA-256 Merkle root hash
 * @param signatureB64 Base64-encoded Ed25519 digital signature
 * @param publicKeyB64 Base64-encoded Ed25519 public verification key
 * @param fromSeq      lower sequence bound
 * @param toSeq        upper sequence bound
 * @param publishedAt  publication timestamp
 */
public record SignedMerkleRootResponse(
        String rootHash,
        String signatureB64,
        String publicKeyB64,
        long fromSeq,
        long toSeq,
        OffsetDateTime publishedAt
) implements Serializable {}
