package com.pulse.control;

import com.pulse.boundary.filter.InvalidTokenException;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.boundary.filter.TokenExpiredException;
import com.pulse.entity.EngineRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("test-secret-key-32-bytes-minimum-length-key-12345");
    }

    @Test
    @DisplayName("issue and verify should return TokenClaims with matching subject and role")
    void testIssueAndVerifyToken() {
        String token = tokenService.issue("alice_engineer", EngineRole.ENGINEER);
        assertThat(token).isNotNull();
        assertThat(token.split("\\.")).hasSize(3);

        TokenClaims claims = tokenService.verify(token);
        assertThat(claims.subject()).isEqualTo("alice_engineer");
        assertThat(claims.role()).isEqualTo(EngineRole.ENGINEER);
        assertThat(claims.expiresAt()).isAfter(claims.issuedAt());
    }

    @Test
    @DisplayName("tampering with the payload section should cause signature verification failure and throw InvalidTokenException")
    void testTamperedPayloadThrowsInvalidTokenException() {
        String token = tokenService.issue("alice_engineer", EngineRole.ENGINEER);
        String[] parts = token.split("\\.");

        // Tamper with payload (elevate to ADMIN)
        String tamperedPayload = "{\"sub\":\"alice_engineer\",\"role\":\"ADMIN\",\"iat\":1234567890,\"exp\":1934567890}";
        String tamperedPayloadBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8));

        String tamperedToken = parts[0] + "." + tamperedPayloadBase64 + "." + parts[2];

        assertThatThrownBy(() -> tokenService.verify(tamperedToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("signature verification failed");
    }

    @Test
    @DisplayName("token with exp in the past should throw TokenExpiredException")
    void testExpiredTokenThrowsTokenExpiredException() {
        Instant pastInstant = Instant.now().minus(2, ChronoUnit.HOURS);
        String expiredToken = tokenService.issue("charlie_admin", EngineRole.ADMIN, pastInstant);

        assertThatThrownBy(() -> tokenService.verify(expiredToken))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("two calls to issue() with the same subject and timestamp should produce identical payload sections")
    void testDeterministicPayloads() {
        Instant fixedInstant = Instant.parse("2026-09-01T12:00:00Z");
        String token1 = tokenService.issue("test_user", EngineRole.ENGINEER, fixedInstant);
        String token2 = tokenService.issue("test_user", EngineRole.ENGINEER, fixedInstant);

        String[] parts1 = token1.split("\\.");
        String[] parts2 = token2.split("\\.");

        assertThat(parts1[1]).isEqualTo(parts2[1]);
        assertThat(token1).isEqualTo(token2);
    }

    @Test
    @DisplayName("invalid token format should throw InvalidTokenException")
    void testMalformedTokenThrowsInvalidTokenException() {
        assertThatThrownBy(() -> tokenService.verify("not.a.valid.jwt.token"))
                .isInstanceOf(InvalidTokenException.class);

        assertThatThrownBy(() -> tokenService.verify("singlepart"))
                .isInstanceOf(InvalidTokenException.class);

        assertThatThrownBy(() -> tokenService.verify(null))
                .isInstanceOf(InvalidTokenException.class);
    }
}
