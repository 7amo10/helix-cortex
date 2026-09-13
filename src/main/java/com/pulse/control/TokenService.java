package com.pulse.control;

import com.pulse.boundary.filter.InvalidTokenException;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.boundary.filter.TokenExpiredException;
import com.pulse.entity.EngineRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for issuing and verifying HMAC-SHA256 JSON Web Tokens.
 * Operates statelessly without third-party JWT libraries.
 */
@ApplicationScoped
public class TokenService {

    public static final long EXPIRY_SECONDS = 3600L;
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String HEADER_BASE64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));

    @Inject
    @ConfigProperty(name = "jwt.secret", defaultValue = "helix-cortex-default-super-secret-key-32-bytes-minimum")
    private String jwtSecret = "helix-cortex-default-super-secret-key-32-bytes-minimum";

    public TokenService() {
    }

    public TokenService(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /**
     * Issues a signed JWT token for the given subject and role.
     *
     * @param subject username or principal identity
     * @param role    role for RBAC authorization
     * @return signed JWT string in header.payload.signature format
     */
    public String issue(String subject, EngineRole role) {
        return issue(subject, role, Instant.now());
    }

    /**
     * Issues a signed JWT with an explicit issued-at timestamp (useful for testing).
     */
    public String issue(String subject, EngineRole role, Instant issuedAt) {
        long iat = issuedAt.getEpochSecond();
        long exp = iat + EXPIRY_SECONDS;

        String payloadJson = String.format("{\"sub\":\"%s\",\"role\":\"%s\",\"iat\":%d,\"exp\":%d}",
                escapeJson(subject), role.name(), iat, exp);
        String payloadBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String content = HEADER_BASE64 + "." + payloadBase64;
        String signatureBase64 = sign(content, jwtSecret);

        return content + "." + signatureBase64;
    }

    /**
     * Verifies the given token signature and expiry.
     *
     * @param token JWT token string
     * @return validated TokenClaims
     * @throws InvalidTokenException if signature is invalid, or format is malformed
     * @throws TokenExpiredException if token expiration time has elapsed
     */
    public TokenClaims verify(String token) {
        if (token == null) {
            throw new InvalidTokenException("Token cannot be null");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("Malformed JWT format. Expected 3 sections, found: " + parts.length);
        }

        String headerBase64 = parts[0];
        String payloadBase64 = parts[1];
        String signatureBase64 = parts[2];

        String contentToVerify = headerBase64 + "." + payloadBase64;
        String expectedSignatureBase64 = sign(contentToVerify, jwtSecret);

        // Constant-time signature comparison to prevent timing attacks
        if (!MessageDigest.isEqual(
                signatureBase64.getBytes(StandardCharsets.UTF_8),
                expectedSignatureBase64.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidTokenException("JWT signature verification failed");
        }

        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid Base64URL in payload", e);
        }

        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

        String subject = extractClaim(payloadJson, "sub");
        String roleStr = extractClaim(payloadJson, "role");
        String iatStr = extractNumberClaim(payloadJson, "iat");
        String expStr = extractNumberClaim(payloadJson, "exp");

        if (subject == null || roleStr == null || expStr == null || iatStr == null) {
            throw new InvalidTokenException("Missing required claims in JWT payload");
        }

        EngineRole role;
        try {
            role = EngineRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid role claim in JWT: " + roleStr);
        }

        long expEpoch;
        long iatEpoch;
        try {
            expEpoch = Long.parseLong(expStr);
            iatEpoch = Long.parseLong(iatStr);
        } catch (NumberFormatException e) {
            throw new InvalidTokenException("Malformed timestamp in JWT claims", e);
        }

        Instant expInstant = Instant.ofEpochSecond(expEpoch);
        Instant iatInstant = Instant.ofEpochSecond(iatEpoch);

        if (Instant.now().isAfter(expInstant)) {
            throw new TokenExpiredException("JWT token expired at " + expInstant);
        }

        return new TokenClaims(subject, role, iatInstant, expInstant);
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractClaim(String json, String claimName) {
        Pattern pattern = Pattern.compile("\"" + claimName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String extractNumberClaim(String json, String claimName) {
        Pattern pattern = Pattern.compile("\"" + claimName + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
