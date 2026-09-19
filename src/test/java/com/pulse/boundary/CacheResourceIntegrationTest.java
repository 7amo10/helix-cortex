package com.pulse.boundary;

import com.pulse.boundary.dto.CacheInvalidateRequest;
import com.pulse.boundary.dto.CacheInvalidateResponse;
import com.pulse.boundary.dto.CacheMetricsResponse;
import com.pulse.boundary.filter.JwtSecurityFilter;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.L4CacheService;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheResourceIntegrationTest {

    private TokenService tokenService;
    private JwtSecurityFilter securityFilter;
    private L4CacheService cacheService;
    private CacheResource cacheResource;

    @Mock
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("helix-cortex-integration-test-secret-key-32-bytes");
        securityFilter = new JwtSecurityFilter(tokenService, null);
        cacheService = new L4CacheService("localhost", 6379, 2000, true);
        cacheResource = new CacheResource(cacheService);
    }

    @AfterEach
    void tearDown() {
        if (cacheService != null) {
            cacheService.destroy();
        }
    }

    @Test
    @DisplayName("Integration: Filter permits valid ADMIN and OPERATOR tokens and populates SecurityContext")
    void testFilterPermitsAdminAndOperator() throws IOException {
        String adminToken = tokenService.issue("super_admin", EngineRole.ADMIN, Instant.now().plusSeconds(3600));
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + adminToken);

        securityFilter.filter(requestContext);

        verify(requestContext, never()).abortWith(any(Response.class));
        verify(requestContext).setSecurityContext(argThat(sc ->
                sc.isUserInRole("ADMIN") && !sc.isUserInRole("ENGINEER")));

        // Test Operator token
        reset(requestContext);
        String operatorToken = tokenService.issue("ops_lead", EngineRole.OPERATOR, Instant.now().plusSeconds(3600));
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + operatorToken);

        securityFilter.filter(requestContext);

        verify(requestContext, never()).abortWith(any(Response.class));
        verify(requestContext).setSecurityContext(argThat(sc ->
                sc.isUserInRole("OPERATOR") && !sc.isUserInRole("ADMIN")));
    }

    @Test
    @DisplayName("Integration: Filter rejects missing and expired tokens")
    void testFilterRejectsUnauthenticated() throws IOException {
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        securityFilter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());
        assertThat(responseCaptor.getValue().getStatus()).isEqualTo(401);

        reset(requestContext);
        String expiredToken = tokenService.issue("old_user", EngineRole.ADMIN, Instant.now().minusSeconds(3600));
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + expiredToken);

        securityFilter.filter(requestContext);

        ArgumentCaptor<Response> expiredCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(expiredCaptor.capture());
        assertThat(expiredCaptor.getValue().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Integration: End-to-end cache metrics and invalidation cycle")
    void testEndToEndCacheLifecycle() {
        // Query initial metrics
        Response metricsResp = cacheResource.getMetrics();
        assertThat(metricsResp.getStatus()).isEqualTo(200);
        CacheMetricsResponse initialMetrics = (CacheMetricsResponse) metricsResp.getEntity();
        assertThat(initialMetrics).isNotNull();

        // Perform real lookup to generate hit/miss
        String testRuleHash = "integ-test-" + System.nanoTime();
        cacheService.getCache().putBytecode(testRuleHash, "IntegRule", "1.0.0", "BYTECODE".getBytes(StandardCharsets.UTF_8));
        var retrieved = cacheService.getCache().getBytecode(testRuleHash);
        assertThat(retrieved).isPresent();

        // Query updated metrics
        Response updatedResp = cacheResource.getMetrics();
        CacheMetricsResponse updatedMetrics = (CacheMetricsResponse) updatedResp.getEntity();
        assertThat(updatedMetrics.hitCount()).isGreaterThan(0L);

        // Invalidate rule via REST endpoint
        CacheInvalidateRequest invalidateReq = new CacheInvalidateRequest("IntegRule", "1.0.0");
        Response invalidateResp = cacheResource.invalidate(invalidateReq);
        assertThat(invalidateResp.getStatus()).isEqualTo(200);
        CacheInvalidateResponse invBody = (CacheInvalidateResponse) invalidateResp.getEntity();
        assertThat(invBody.status()).isEqualTo("SUCCESS");
        assertThat(invBody.ruleName()).isEqualTo("IntegRule");
    }
}
