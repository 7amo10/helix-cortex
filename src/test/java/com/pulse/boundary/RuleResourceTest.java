package com.pulse.boundary;

import com.pulse.boundary.dto.ExecutionRequest;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.control.RuleSessionControl;
import com.pulse.control.RuleSessionRepository;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleResourceTest {

    @Mock
    private RuleSessionControl control;

    @Mock
    private RuleSessionRepository repo;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Principal principal;

    private RuleResource resource;

    @BeforeEach
    void setUp() {
        resource = new RuleResource(control, repo, securityContext);
    }

    @Test
    @DisplayName("POST /rules/compile with valid RuleRequest returns 201 Created")
    void testCompileRuleSuccess() throws Exception {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");

        RuleSession session = new RuleSession("engineer_1", "{}", "check:1.0", SessionStatus.COMPILED);
        when(control.compileAndSave(any(RuleRequest.class), eq("engineer_1"))).thenReturn(session);

        RuleRequest req = new RuleRequest("check", "1.0", "x > 10", Map.of("x", "int"));
        Response response = resource.compileRule(req);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getEntity()).isEqualTo(session);
    }

    @Test
    @DisplayName("POST /rules/compile with missing fields returns 400 Bad Request")
    void testCompileRuleBadRequest() throws Exception {
        RuleRequest invalidReq = new RuleRequest(null, "1.0", null, Map.of());
        Response response = resource.compileRule(invalidReq);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("POST /rules/execute/{sessionId} where session does not exist returns 404")
    void testExecuteRuleNotFound() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        Response response = resource.executeRule(999L, new ExecutionRequest(Map.of("x", 15)));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("POST /rules/execute/{sessionId} with valid session returns 200 OK and OpcodeMetric")
    void testExecuteRuleSuccess() {
        RuleSession session = new RuleSession("engineer_1", "{}", "check:1.0", SessionStatus.COMPILED);
        when(repo.findById(1L)).thenReturn(Optional.of(session));

        OpcodeMetric metric = new OpcodeMetric(session, 15L, 25000L, false);
        when(control.executeAndSave(eq(1L), any())).thenReturn(metric);

        Response response = resource.executeRule(1L, new ExecutionRequest(Map.of("x", 15)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(metric);
    }

    @Test
    @DisplayName("GET /rules/sessions with ADMIN role returns 200 OK with all sessions")
    void testGetSessionsAsAdmin() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole("ADMIN")).thenReturn(true);
        when(repo.findAll()).thenReturn(List.of(new RuleSession("eng_1", "{}", "r-1", SessionStatus.COMPILED)));

        Response response = resource.getSessions();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((List<?>) response.getEntity()).hasSize(1);
    }

    @Test
    @DisplayName("GET /rules/sessions with ENGINEER role returns 403 Forbidden")
    void testGetSessionsAsEngineerReturns403() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole("ADMIN")).thenReturn(false);

        Response response = resource.getSessions();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("GET /rules/sessions/mine with ENGINEER returns 200 OK with only their sessions")
    void testGetMySessions() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");

        RuleSession mySession = new RuleSession("engineer_1", "{}", "r-1", SessionStatus.COMPILED);
        when(repo.findByEngineerId("engineer_1")).thenReturn(List.of(mySession));

        Response response = resource.getMySessions();

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<RuleSession> list = (List<RuleSession>) response.getEntity();
        assertThat(list).containsExactly(mySession);
    }

    @Test
    @DisplayName("GET /rules/sessions/high-density with ADMIN role returns 200 OK")
    void testGetHighDensitySessions() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole("ADMIN")).thenReturn(true);
        when(repo.findHighOpcodeDensitySessions(500L)).thenReturn(Collections.emptyList());

        Response response = resource.getHighDensitySessions(500L);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((List<?>) response.getEntity()).isEmpty();
    }
}
