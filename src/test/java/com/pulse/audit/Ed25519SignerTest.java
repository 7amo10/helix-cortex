package com.pulse.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Ed25519Signer Cryptographic Tests")
class Ed25519SignerTest {

    @Test
    @DisplayName("Sign and verify roundtrip succeeds on arbitrary payload")
    void testSignAndVerifyRoundTrip() throws Exception {
        Ed25519Signer signer = new Ed25519Signer();
        byte[] payload = "helix:audit:rootHash:abcdef1234".getBytes(StandardCharsets.UTF_8);

        byte[] signature = signer.sign(payload);
        assertNotNull(signature);
        assertEquals(64, signature.length);

        boolean valid = signer.verify(payload, signature);
        assertTrue(valid);
    }

    @Test
    @DisplayName("Verify fails if payload is mutated")
    void testVerifyFailsOnTamperedPayload() throws Exception {
        Ed25519Signer signer = new Ed25519Signer();
        byte[] payload = "helix:audit:rootHash:abcdef1234".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "helix:audit:rootHash:abcdef1235".getBytes(StandardCharsets.UTF_8);

        byte[] signature = signer.sign(payload);
        boolean valid = signer.verify(tampered, signature);
        assertFalse(valid);
    }

    @Test
    @DisplayName("Verify fails if signature is altered")
    void testVerifyFailsOnAlteredSignature() throws Exception {
        Ed25519Signer signer = new Ed25519Signer();
        byte[] payload = "testPayload".getBytes(StandardCharsets.UTF_8);

        byte[] signature = signer.sign(payload);
        signature[0] ^= 0xFF; // corrupt one byte
        boolean valid = signer.verify(payload, signature);
        assertFalse(valid);
    }

    @Test
    @DisplayName("Public key is exportable as non-empty Base64 string")
    void testGetPublicKeyBase64() {
        Ed25519Signer signer = new Ed25519Signer();
        String pubKeyB64 = signer.getPublicKeyBase64();
        assertNotNull(pubKeyB64);
        assertFalse(pubKeyB64.isBlank());
    }

    @Test
    @DisplayName("External verification with exported public key verifies signature")
    void testVerifyWithExportedPublicKey() throws Exception {
        Ed25519Signer signer = new Ed25519Signer();
        byte[] payload = "immutableCheckpointRoot".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(payload);
        String pubKeyB64 = signer.getPublicKeyBase64();

        boolean verified = Ed25519Signer.verifyWithPublicKey(pubKeyB64, payload, signature);
        assertTrue(verified);
    }
}
