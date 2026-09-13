package com.pulse.boundary.filter;

import com.pulse.entity.EngineRole;
import java.time.Instant;

/**
 * Record holding parsed and verified claims from a JWT token.
 */
public record TokenClaims(
        String subject,
        EngineRole role,
        Instant issuedAt,
        Instant expiresAt
) {
}
