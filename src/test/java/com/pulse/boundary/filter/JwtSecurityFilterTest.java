package com.pulse.boundary.filter;

import com.pulse.control.AuditLogRepository;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtSecurityFilterTest {

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UriInfo uriInfo;

    @Mock
    private SecurityContext originalSecurityContext;

    private TokenService tokenService;
    private JwtSecurityFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("my-secure-32-byte-test-secret-key-12345");
        filter = new JwtSecurityFilter(tokenService, auditLogRepository);
    }

    @Test
    @DisplayName("request with no Authorization header should abort with 401 and application/problem+json")
    void testMissingAuthorizationHeader() throws IOException {
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
        assertThat((String) response.getEntity()).contains("Missing or malformed Authorization header");
    }

    @Test
    @DisplayName("request with invalid or tampered token should abort with 403 and application/problem+json")
    void testInvalidTokenAborts403() throws IOException {
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer invalid.tampered.token");

        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("request with expired token should abort with 401 and application/problem+json")
    void testExpiredTokenAborts401() throws IOException {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String expiredToken = tokenService.issue("expired_user", EngineRole.ENGINEER, past);
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + expiredToken);

        filter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
        assertThat((String) response.getEntity()).contains("Token expired");
    }

    @Test
    @DisplayName("request with valid token should set SecurityContext and log audit entry")
    void testValidTokenSetsSecurityContext() throws IOException {
        String validToken = tokenService.issue("jane_engineer", EngineRole.ENGINEER);
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + validToken);
        when(requestContext.getSecurityContext()).thenReturn(originalSecurityContext);
        when(originalSecurityContext.isSecure()).thenReturn(false);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("rules/compile");
        when(requestContext.getMethod()).thenReturn("POST");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());

        ArgumentCaptor<SecurityContext> scCaptor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(requestContext).setSecurityContext(scCaptor.capture());

        SecurityContext securityContext = scCaptor.getValue();
        assertThat(securityContext.getUserPrincipal().getName()).isEqualTo("jane_engineer");
        assertThat(securityContext.isUserInRole("ENGINEER")).isTrue();
        assertThat(securityContext.isUserInRole("ADMIN")).isFalse();

        verify(auditLogRepository).logAsync("jane_engineer", "rules/compile", "POST", true);
    }
}
