package com.pulse.boundary.filter;

import com.pulse.control.AuditLogRepository;
import com.pulse.control.TokenService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * JAX-RS container request filter intercepting all @Secured endpoints.
 * Validates JWT Bearer tokens and sets up the security context.
 */
@Secured
@Provider
@Priority(Priorities.AUTHENTICATION)
public class JwtSecurityFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PROBLEM_JSON_TYPE = "application/problem+json";

    @Inject
    private TokenService tokenService;

    @Inject
    private AuditLogRepository auditLogRepository;

    public JwtSecurityFilter() {
    }

    public JwtSecurityFilter(TokenService tokenService, AuditLogRepository auditLogRepository) {
        this.tokenService = tokenService;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            abortWith(requestContext, Response.Status.UNAUTHORIZED, "Unauthorized",
                    "Missing or malformed Authorization header. Expected 'Bearer <token>'.");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            abortWith(requestContext, Response.Status.UNAUTHORIZED, "Unauthorized",
                    "Bearer token string is empty.");
            return;
        }

        try {
            TokenClaims claims = tokenService.verify(token);
            boolean isSecure = requestContext.getSecurityContext() != null &&
                    requestContext.getSecurityContext().isSecure();
            requestContext.setSecurityContext(new JwtSecurityContext(claims, isSecure));

            if (auditLogRepository != null) {
                String path = requestContext.getUriInfo() != null ?
                        requestContext.getUriInfo().getPath() : "/";
                String method = requestContext.getMethod();
                auditLogRepository.logAsync(claims.subject(), path, method, true);
            }
        } catch (TokenExpiredException e) {
            abortWith(requestContext, Response.Status.UNAUTHORIZED, "Unauthorized",
                    "Token expired: " + e.getMessage());
        } catch (InvalidTokenException e) {
            abortWith(requestContext, Response.Status.FORBIDDEN, "Forbidden",
                    "Invalid token: " + e.getMessage());
        } catch (Exception e) {
            abortWith(requestContext, Response.Status.FORBIDDEN, "Forbidden",
                    "Authentication failed: " + e.getMessage());
        }
    }

    private void abortWith(ContainerRequestContext requestContext, Response.Status status, String title, String detail) {
        String problemJson = String.format("{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}",
                status.getStatusCode(), title, escapeJson(detail));
        requestContext.abortWith(Response.status(status)
                .type(PROBLEM_JSON_TYPE)
                .entity(problemJson)
                .build());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    public void setTokenService(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public void setAuditLogRepository(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }
}
