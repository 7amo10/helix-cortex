package com.pulse.boundary;

import com.pulse.boundary.dto.CacheInvalidateRequest;
import com.pulse.boundary.dto.CacheInvalidateResponse;
import com.pulse.boundary.dto.CacheMetricsResponse;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.Secured;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.L4CacheService;
import com.pulse.entity.EngineRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheResourceTest {

    @Mock
    private L4CacheService cacheService;

    private CacheResource resource;

    @BeforeEach
    void setUp() {
        resource = new CacheResource(cacheService);
    }

    @Test
    @DisplayName("GET /cache/l4/metrics returns 200 OK with CacheMetricsResponse")
    void testGetMetricsSuccess() {
        CacheMetricsResponse mockMetrics = new CacheMetricsResponse(
                42L, 8L, true, 0.84, false, "localhost", 6379
        );
        when(cacheService.getMetrics()).thenReturn(mockMetrics);

        Response response = resource.getMetrics();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(mockMetrics);
    }

    @Test
    @DisplayName("POST /cache/l4/invalidate with valid ruleName broadcasts eviction and returns 200 OK")
    void testInvalidateSuccess() {
        CacheInvalidateRequest req = new CacheInvalidateRequest("FraudRule", "1.0.0");

        Response response = resource.invalidate(req);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isInstanceOf(CacheInvalidateResponse.class);
        CacheInvalidateResponse body = (CacheInvalidateResponse) response.getEntity();
        assertThat(body.status()).isEqualTo("SUCCESS");
        assertThat(body.ruleName()).isEqualTo("FraudRule");
        verify(cacheService).invalidate("FraudRule", "1.0.0");
    }

    @Test
    @DisplayName("POST /cache/l4/invalidate with null or blank ruleName returns 400 Bad Request")
    void testInvalidateBadRequest() {
        Response responseNull = resource.invalidate(null);
        assertThat(responseNull.getStatus()).isEqualTo(400);

        Response responseEmpty = resource.invalidate(new CacheInvalidateRequest("   "));
        assertThat(responseEmpty.getStatus()).isEqualTo(400);

        verify(cacheService, never()).invalidate(anyString(), any());
    }

    @Test
    @DisplayName("CacheResource and endpoints enforce RBAC with Secured and RolesAllowed(ADMIN, OPERATOR)")
    void testRbacSecurityAnnotations() throws NoSuchMethodException {
        // Verify class-level @Secured annotation
        assertThat(CacheResource.class.isAnnotationPresent(Secured.class)).isTrue();

        // Verify getMetrics @RolesAllowed annotation
        Method getMetricsMethod = CacheResource.class.getMethod("getMetrics");
        RolesAllowed getRoles = getMetricsMethod.getAnnotation(RolesAllowed.class);
        assertThat(getRoles).isNotNull();
        assertThat(Arrays.asList(getRoles.value())).containsExactlyInAnyOrder("ADMIN", "OPERATOR");

        // Verify invalidate @RolesAllowed annotation
        Method invalidateMethod = CacheResource.class.getMethod("invalidate", CacheInvalidateRequest.class);
        RolesAllowed invalidateRoles = invalidateMethod.getAnnotation(RolesAllowed.class);
        assertThat(invalidateRoles).isNotNull();
        assertThat(Arrays.asList(invalidateRoles.value())).containsExactlyInAnyOrder("ADMIN", "OPERATOR");
    }

    @Test
    @DisplayName("Verify JWT SecurityContext evaluates roles properly for ADMIN and OPERATOR")
    void testSecurityContextRoles() {
        Instant now = Instant.now();
        TokenClaims adminClaims = new TokenClaims("admin_user", EngineRole.ADMIN, now, now.plusSeconds(3600));
        JwtSecurityContext adminContext = new JwtSecurityContext(adminClaims);
        assertThat(adminContext.isUserInRole("ADMIN")).isTrue();
        assertThat(adminContext.isUserInRole("OPERATOR")).isFalse();
        assertThat(adminContext.isUserInRole("ENGINEER")).isFalse();

        TokenClaims operatorClaims = new TokenClaims("operator_user", EngineRole.OPERATOR, now, now.plusSeconds(3600));
        JwtSecurityContext operatorContext = new JwtSecurityContext(operatorClaims);
        assertThat(operatorContext.isUserInRole("OPERATOR")).isTrue();
        assertThat(operatorContext.isUserInRole("ADMIN")).isFalse();
        assertThat(operatorContext.isUserInRole("ENGINEER")).isFalse();

        TokenClaims engineerClaims = new TokenClaims("engineer_user", EngineRole.ENGINEER, now, now.plusSeconds(3600));
        JwtSecurityContext engineerContext = new JwtSecurityContext(engineerClaims);
        assertThat(engineerContext.isUserInRole("ADMIN")).isFalse();
        assertThat(engineerContext.isUserInRole("OPERATOR")).isFalse();
        assertThat(engineerContext.isUserInRole("ENGINEER")).isTrue();
    }
}
