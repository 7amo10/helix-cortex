package com.pulse.boundary.filter;

import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/**
 * Custom JAX-RS SecurityContext populating caller identity and roles from verified JWT claims.
 */
public class JwtSecurityContext implements SecurityContext {

    private final TokenClaims claims;
    private final boolean secure;

    public JwtSecurityContext(TokenClaims claims) {
        this(claims, false);
    }

    public JwtSecurityContext(TokenClaims claims, boolean secure) {
        this.claims = claims;
        this.secure = secure;
    }

    @Override
    public Principal getUserPrincipal() {
        return () -> claims.subject();
    }

    @Override
    public boolean isUserInRole(String role) {
        if (role == null || claims.role() == null) {
            return false;
        }
        return claims.role().name().equalsIgnoreCase(role);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String getAuthenticationScheme() {
        return "BEARER";
    }

    public TokenClaims getClaims() {
        return claims;
    }
}
