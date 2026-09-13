package com.pulse;

import com.pulse.boundary.AuthResource;
import com.pulse.boundary.RuleResource;
import com.pulse.boundary.dto.LoginRequest;
import com.pulse.boundary.dto.LoginResponse;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.JwtSecurityFilter;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.AuditLogRepository;
import com.pulse.control.EngineerAccountRepository;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.EngineerAccount;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Sprint1VerificationTest {

    @Mock
    private EngineerAccountRepository accountRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private UriInfo uriInfo;

    private TokenService tokenService;
    private AuthResource authResource;
    private JwtSecurityFilter securityFilter;
    private RuleResource ruleResource;

    private static final String ADMIN_HASH = "2048:aGVsaXhjb3J0ZXhzYWx0MQ==:V0DWISnULJpoTO4F7SX7qpvQmUcOjsJXzGDV7DMakuA=";
    private static final String ENGINEER_HASH = "2048:aGVsaXhjb3J0ZXhzYWx0Mg==:Ukez5khs1pitrN6WJxhWxfYiMiFEmNKFeYcfH82ta2c=";

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("sprint-1-super-secret-production-grade-key-32-chars");
        authResource = new AuthResource(accountRepository, tokenService, null);
        securityFilter = new JwtSecurityFilter(tokenService, auditLogRepository);
        ruleResource = new RuleResource();
    }

    @Test
    @DisplayName("Verification 1: initial-data.sql exists and contains admin and engineer_1 seeds")
    void testInitialDataSql() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("META-INF/initial-data.sql");
        assertThat(is).isNotNull();

        String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("admin");
        assertThat(sql).contains("engineer_1");
        assertThat(sql).contains("ADMIN");
        assertThat(sql).contains("ENGINEER");
    }

    @Test
    @DisplayName("Verification 2: POST /api/v1/auth/login with admin:admin123 returns 200 and valid JWT")
    void testAdminLogin() {
        EngineerAccount admin = new EngineerAccount("admin", ADMIN_HASH, EngineRole.ADMIN);
        when(accountRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        Response response = authResource.login(new LoginRequest("admin", "admin123"));

        assertThat(response.getStatus()).isEqualTo(200);
        LoginResponse entity = (LoginResponse) response.getEntity();
        assertThat(entity.token()).isNotNull().isNotBlank();
        assertThat(entity.role()).isEqualTo(EngineRole.ADMIN);
        assertThat(entity.expiresIn()).isEqualTo(3600L);

        TokenClaims claims = tokenService.verify(entity.token());
        assertThat(claims.subject()).isEqualTo("admin");
        assertThat(claims.role()).isEqualTo(EngineRole.ADMIN);
    }

    @Test
    @DisplayName("Verification 3: POST /api/v1/auth/login with engineer_1:engineer123 returns 200 and valid JWT")
    void testEngineerLogin() {
        EngineerAccount engineer = new EngineerAccount("engineer_1", ENGINEER_HASH, EngineRole.ENGINEER);
        when(accountRepository.findByUsername("engineer_1")).thenReturn(Optional.of(engineer));

        Response response = authResource.login(new LoginRequest("engineer_1", "engineer123"));

        assertThat(response.getStatus()).isEqualTo(200);
        LoginResponse entity = (LoginResponse) response.getEntity();
        assertThat(entity.role()).isEqualTo(EngineRole.ENGINEER);

        TokenClaims claims = tokenService.verify(entity.token());
        assertThat(claims.subject()).isEqualTo("engineer_1");
        assertThat(claims.role()).isEqualTo(EngineRole.ENGINEER);
    }

    @Test
    @DisplayName("Verification 4: GET /api/v1/rules/sessions without token returns 401")
    void testGetRulesSessionsWithoutTokenReturns401() throws Exception {
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        securityFilter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("Verification 5: GET /api/v1/rules/sessions with expired token returns 401")
    void testGetRulesSessionsWithExpiredTokenReturns401() throws Exception {
        Instant expiredTime = Instant.now().minus(3, ChronoUnit.HOURS);
        String expiredToken = tokenService.issue("admin", EngineRole.ADMIN, expiredTime);
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + expiredToken);

        securityFilter.filter(requestContext);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("Verification 6: GET /api/v1/rules/sessions with valid ENGINEER token passes filter and returns 200")
    void testGetRulesSessionsWithValidTokenReturns200() throws Exception {
        String token = tokenService.issue("engineer_1", EngineRole.ENGINEER);
        when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("rules/sessions");
        when(requestContext.getMethod()).thenReturn("GET");

        securityFilter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());

        ArgumentCaptor<SecurityContext> scCaptor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(requestContext).setSecurityContext(scCaptor.capture());
        SecurityContext sc = scCaptor.getValue();
        assertThat(sc.getUserPrincipal().getName()).isEqualTo("engineer_1");
        assertThat(sc.isUserInRole("ENGINEER")).isTrue();

        Response ruleResponse = ruleResource.getSessions();
        assertThat(ruleResponse.getStatus()).isEqualTo(200);
        assertThat((List<?>) ruleResponse.getEntity()).isEmpty();
    }
}
