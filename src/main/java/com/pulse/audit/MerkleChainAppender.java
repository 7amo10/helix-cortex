package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Service orchestrating Merkle leaf chaining and incremental hash accumulation for audit records.
 *
 * <p>Computes {@code leaf_hash = SHA-256(prev_hash || event_hash)}, handling genesis records
 * where {@code prev_hash} is {@code null}. Guarantees strict determinism and cryptographic integrity.</p>
 */
@ApplicationScoped
public class MerkleChainAppender {

    private static final String SHA_256 = "SHA-256";

    /**
     * Chains the previous record's leaf hash and current event hash into a new Merkle leaf hash.
     *
     * @param prevHash  leaf hash of the previous record (or null for genesis record)
     * @param eventHash SHA-256 digest of the current record's canonical event JSON
     * @return 64-character hexadecimal chained leaf hash
     */
    public String appendLeaf(String prevHash, String eventHash) {
        if (eventHash == null) {
            eventHash = "";
        }
        String combined = (prevHash != null ? prevHash : "") + eventHash;
        return sha256Hex(combined);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }
}
