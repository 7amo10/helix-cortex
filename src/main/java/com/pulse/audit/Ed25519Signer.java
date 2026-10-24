package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Enterprise cryptographic service managing Ed25519 digital signatures using standard Java 21 java.security APIs.
 *
 * <p>Provides private key signing and public key signature verification for periodic Merkle root publication,
 * ensuring tamper-evident non-repudiation of rule execution audit checkpoints.</p>
 */
@ApplicationScoped
public class Ed25519Signer {

    private static final Logger log = LoggerFactory.getLogger(Ed25519Signer.class);
    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    /**
     * Constructs a new Ed25519Signer generating a secure in-memory Ed25519 KeyPair.
     */
    public Ed25519Signer() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair kp = kpg.generateKeyPair();
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
            log.info("Ed25519Signer initialized successfully with Java 21 standard Ed25519 key pair");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 algorithm not available on this JVM", e);
        }
    }

    /**
     * Constructs an Ed25519Signer with explicit key pair components.
     *
     * @param privateKey private signing key
     * @param publicKey  public verification key
     */
    public Ed25519Signer(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey cannot be null");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey cannot be null");
    }

    /**
     * Cryptographically signs the provided byte array with the managed Ed25519 private key.
     *
     * @param data payload bytes to sign
     * @return 64-byte Ed25519 digital signature
     * @throws SignatureException if signature generation fails
     */
    public byte[] sign(byte[] data) throws SignatureException {
        Objects.requireNonNull(data, "data cannot be null");
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SignatureException("Failed to generate Ed25519 signature", e);
        }
    }

    /**
     * Verifies an Ed25519 digital signature against the managed public key.
     *
     * @param data      original payload bytes
     * @param signature candidate signature bytes
     * @return true if valid, false otherwise
     * @throws SignatureException if verification mechanism fails
     */
    public boolean verify(byte[] data, byte[] signature) throws SignatureException {
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (SignatureException e) {
            log.debug("Ed25519 signature verification failed or signature malformed: {}", e.getMessage());
            return false;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SignatureException("Failed to verify Ed25519 signature", e);
        }
    }

    /**
     * Exports the standard X.509 encoded public key as a Base64 string for external verification.
     *
     * @return Base64-encoded public key
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Static utility verifying an Ed25519 digital signature against an external Base64-encoded X.509 public key.
     *
     * @param publicKeyBase64 Base64-encoded X.509 public key string
     * @param data            original payload bytes
     * @param signature       candidate signature bytes
     * @return true if signature is cryptographically valid, false otherwise
     * @throws SignatureException if public key reconstruction or verification fails
     */
    public static boolean verifyWithPublicKey(String publicKeyBase64, byte[] data, byte[] signature) throws SignatureException {
        Objects.requireNonNull(publicKeyBase64, "publicKeyBase64 cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey reconstructedKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(reconstructedKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (SignatureException e) {
            log.debug("External Ed25519 signature verification failed or signature malformed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            throw new SignatureException("Failed to verify Ed25519 signature with external public key", e);
        }
    }
}
