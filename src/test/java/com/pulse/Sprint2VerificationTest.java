package com.pulse;

import com.helix.api.CompiledRule;
import com.helix.api.RuleEngine;
import com.helix.api.profiler.Profiler;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.GlobalExceptionMapper;
import com.pulse.boundary.RuleResource;
import com.pulse.boundary.dto.ExecutionRequest;
import com.pulse.boundary.dto.ProblemDetail;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.control.HelixProducer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Sprint2VerificationTest {

    private HelixProducer helixProducer;
    private RuleEngine realRuleEngine;
    private Profiler realProfiler;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Principal principal;

    private RuleSessionControl control;
    private RuleResource ruleResource;
    private GlobalExceptionMapper exceptionMapper;

    private final List<RuleSession> memoryDatabase = new ArrayList<>();
    private long idSequence = 1L;

    @BeforeEach
    void setUp() {
        helixProducer = new HelixProducer();
        realRuleEngine = helixProducer.produceRuleEngine();
        realProfiler = helixProducer.produceProfiler(realRuleEngine);

        // In-memory simulation of repository
        lenient().when(sessionRepository.save(any(RuleSession.class))).thenAnswer(invocation -> {
            RuleSession s = invocation.getArgument(0);
            if (s.getId() == null) {
                try {
                    var idField = RuleSession.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(s, idSequence++);
                } catch (Exception ignored) {}
                memoryDatabase.add(s);
            }
            return s;
        });

        lenient().when(sessionRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return memoryDatabase.stream().filter(s -> s.getId().equals(id)).findFirst();
        });

        lenient().when(sessionRepository.findAll()).thenAnswer(inv -> List.copyOf(memoryDatabase));

        lenient().when(sessionRepository.findByEngineerId(anyString())).thenAnswer(inv -> {
            String eng = inv.getArgument(0);
            return memoryDatabase.stream().filter(s -> eng.equals(s.getEngineerId())).toList();
        });

        lenient().when(sessionRepository.findHighOpcodeDensitySessions(anyLong())).thenAnswer(inv -> {
            long thresh = inv.getArgument(0);
            return memoryDatabase.stream()
                    .filter(s -> s.getMetrics().stream().anyMatch(m -> m.getTotalOpcodeCount() >= thresh))
                    .toList();
        });

        control = new RuleSessionControl(realRuleEngine, sessionRepository);
        ruleResource = new RuleResource(control, sessionRepository, securityContext);
        exceptionMapper = new GlobalExceptionMapper();
    }

    @Test
    @DisplayName("Verification 1: Real HelixProducer produces operational RuleEngine and Profiler")
    void testHelixProducerIntegration() throws Exception {
        assertThat(realRuleEngine).isNotNull();
        assertThat(realProfiler).isNotNull();
        assertThat(realProfiler.isRunning()).isFalse();

        RuleSchema schema = new RuleSchema("v-rule", "1.0", "desc", "TEST", "x > 5", Map.of("x", Integer.class));
        CompiledRule compiled = realRuleEngine.compile(schema);
        assertThat(compiled).isNotNull();
        assertThat(compiled.getName()).isEqualTo("v-rule");
    }

    @Test
    @DisplayName("Verification 2: Full Lifecycle — Compile rule via Control and verify RuleSession persistence")
    void testRuleCompilationLifecycle() throws Exception {
        RuleRequest req = new RuleRequest("check", "1.0", "return x > 10", Map.of("x", "int"));
        RuleSession session = control.compileAndSave(req, "engineer_1");

        assertThat(session.getId()).isNotNull();
        assertThat(session.getEngineerId()).isEqualTo("engineer_1");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPILED);
        assertThat(session.getCompiledRuleId()).isEqualTo("check:1.0");
        assertThat(session.getRuleJson()).contains("check");
        assertThat(session.getMetrics()).isEmpty();
    }

    @Test
    @DisplayName("Verification 3: Full Lifecycle — Execute compiled rule via Control and verify OpcodeMetric")
    void testRuleExecutionLifecycle() throws Exception {
        RuleRequest req = new RuleRequest("check", "1.0", "return x > 10", Map.of("x", "int"));
        RuleSession session = control.compileAndSave(req, "engineer_1");

        OpcodeMetric metric = control.executeAndSave(session.getId(), Map.of("x", 15));

        assertThat(metric).isNotNull();
        assertThat(metric.getSession()).isEqualTo(session);
        assertThat(metric.getExecutionTimeNanos()).isGreaterThan(0L);
        assertThat(metric.getTotalOpcodeCount()).isGreaterThan(0L);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXECUTED);
        assertThat(session.getMetrics()).hasSize(1);
    }

    @Test
    @DisplayName("Verification 4: Full Lifecycle — High-density queries retrieve executed sessions")
    void testHighDensitySessionQuery() throws Exception {
        RuleRequest req = new RuleRequest("check", "1.0", "return x > 10", Map.of("x", "int"));
        RuleSession session = control.compileAndSave(req, "engineer_1");
        control.executeAndSave(session.getId(), Map.of("x", 15));

        List<RuleSession> highDensity = sessionRepository.findHighOpcodeDensitySessions(0L);
        assertThat(highDensity).hasSize(1);
        assertThat(highDensity.get(0).getId()).isEqualTo(session.getId());
    }

    @Test
    @DisplayName("Verification 5: Boundary Lifecycle — POST /rules/compile and POST /rules/execute via RuleResource")
    void testRuleResourceLifecycle() throws Exception {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");

        RuleRequest req = new RuleRequest("tax_calc", "1.0", "x * 2 > 20", Map.of("x", "int"));
        Response compileResp = ruleResource.compileRule(req);
        assertThat(compileResp.getStatus()).isEqualTo(201);
        RuleSession session = (RuleSession) compileResp.getEntity();

        Response execResp = ruleResource.executeRule(session.getId(), new ExecutionRequest(Map.of("x", 15)));
        assertThat(execResp.getStatus()).isEqualTo(200);
        OpcodeMetric metric = (OpcodeMetric) execResp.getEntity();
        assertThat(metric.getExecutionTimeNanos()).isGreaterThan(0L);

        // Admin gets all sessions
        when(securityContext.isUserInRole("ADMIN")).thenReturn(true);
        Response allResp = ruleResource.getSessions();
        assertThat(allResp.getStatus()).isEqualTo(200);
        assertThat((List<?>) allResp.getEntity()).hasSize(1);
    }

    @Test
    @DisplayName("Verification 6: Error handling — GlobalExceptionMapper transforms compilation error to 400 problem+json")
    void testGlobalExceptionMapperIntegration() {
        com.helix.api.RuleCompilationException ex = new com.helix.api.RuleCompilationException("Syntax error at line 1");
        Response response = exceptionMapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.title()).isEqualTo("Bad Request");
    }
}
