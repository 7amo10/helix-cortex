package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.Rule;
import com.helix.api.RuleEngine;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleSessionControlTest {

    @Mock
    private RuleEngine ruleEngine;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private CompiledRule compiledRule;

    private RuleSessionControl control;

    @BeforeEach
    void setUp() {
        control = new RuleSessionControl(ruleEngine, sessionRepository);
    }

    @Test
    @DisplayName("Class-level annotation must be @Transactional(TxType.MANDATORY)")
    void testTransactionalAnnotation() {
        Transactional annotation = RuleSessionControl.class.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(TxType.MANDATORY);
    }

    @Test
    @DisplayName("compileAndSave with valid expression returns RuleSession with COMPILED status")
    void testCompileAndSave() throws Exception {
        when(compiledRule.getName()).thenReturn("check-rule");
        when(compiledRule.getVersion()).thenReturn("1.0");
        when(ruleEngine.compile(any(Rule.class))).thenReturn(compiledRule);
        when(sessionRepository.save(any(RuleSession.class))).thenAnswer(inv -> inv.getArgument(0));

        RuleRequest req = new RuleRequest("check-rule", "1.0", "return x > 10", Map.of("x", "int"));
        RuleSession session = control.compileAndSave(req, "engineer_1");

        assertThat(session).isNotNull();
        assertThat(session.getEngineerId()).isEqualTo("engineer_1");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPILED);
        assertThat(session.getCompiledRuleId()).isEqualTo("check-rule:1.0");

        verify(ruleEngine).compile(any(Rule.class));
        verify(sessionRepository).save(any(RuleSession.class));
    }

    @Test
    @DisplayName("executeAndSave with valid variables returns OpcodeMetric with executionTimeNanos > 0 and status EXECUTED")
    void testExecuteAndSaveSuccess() throws Exception {
        RuleSession session = new RuleSession("engineer_1", "{\"ruleName\":\"check\",\"ruleVersion\":\"1.0\",\"expression\":\"x > 10\"}", "check:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        when(compiledRule.getName()).thenReturn("check");
        when(compiledRule.getVersion()).thenReturn("1.0");
        when(ruleEngine.compile(any(Rule.class))).thenReturn(compiledRule);

        // Pre-warm cache via compileAndSave
        control.compileAndSave(new RuleRequest("check", "1.0", "x > 10", Map.of("x", "int")), "engineer_1");

        ExecutionResult successResult = ExecutionResult.success(true, 42000L);
        when(ruleEngine.execute(eq(compiledRule), any(ExecutionContext.class))).thenReturn(successResult);
        when(sessionRepository.save(any(RuleSession.class))).thenAnswer(inv -> inv.getArgument(0));

        OpcodeMetric metric = control.executeAndSave(1L, Map.of("x", 15));

        assertThat(metric).isNotNull();
        assertThat(metric.getExecutionTimeNanos()).isEqualTo(42000L);
        assertThat(metric.getTotalOpcodeCount()).isGreaterThan(0L);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXECUTED);

        verify(ruleEngine).execute(eq(compiledRule), any(ExecutionContext.class));
        verify(sessionRepository, atLeastOnce()).save(session);
    }

    @Test
    @DisplayName("executeAndSave with failed execution sets session status to FAILED")
    void testExecuteAndSaveFailure() throws Exception {
        RuleSession session = new RuleSession("engineer_1", "{\"ruleName\":\"check\",\"ruleVersion\":\"1.0\",\"expression\":\"x > 10\"}", "check:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(2L)).thenReturn(Optional.of(session));

        when(compiledRule.getName()).thenReturn("check");
        when(compiledRule.getVersion()).thenReturn("1.0");
        when(ruleEngine.compile(any(Rule.class))).thenReturn(compiledRule);

        control.compileAndSave(new RuleRequest("check", "1.0", "x > 10", Map.of("x", "int")), "engineer_1");

        ExecutionResult failResult = ExecutionResult.failure(new IllegalArgumentException("Type mismatch"), 12000L);
        when(ruleEngine.execute(eq(compiledRule), any(ExecutionContext.class))).thenReturn(failResult);
        when(sessionRepository.save(any(RuleSession.class))).thenAnswer(inv -> inv.getArgument(0));

        OpcodeMetric metric = control.executeAndSave(2L, Map.of("x", "invalid-string"));

        assertThat(metric).isNotNull();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);
        verify(sessionRepository, atLeastOnce()).save(session);
    }
}
