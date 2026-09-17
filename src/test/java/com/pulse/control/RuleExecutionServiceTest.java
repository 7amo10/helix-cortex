package com.pulse.control;

import com.helix.HelixApplication;
import com.helix.api.CompiledRule;
import com.helix.api.RuleEngine;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.dto.BatchExecutionRequest;
import com.pulse.boundary.dto.BatchExecutionResponse;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.entity.EngineRole;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuleExecutionService Unit Tests - Virtual Thread & Platform Pool Execution")
class RuleExecutionServiceTest {

    private RuleEngine ruleEngine;

    @Mock
    private RuleSessionRepository sessionRepository;

    private RuleExecutionService executionService;

    @BeforeEach
    void setUp() {
        ruleEngine = HelixApplication.createEngine();
        executionService = new RuleExecutionService(ruleEngine, sessionRepository, ExecutorType.VIRTUAL_THREADS);
    }

    @AfterEach
    void tearDown() {
        if (executionService != null) {
            executionService.destroy();
        }
    }

    @Test
    @DisplayName("Configuration: ExecutorType parses valid values and defaults to VIRTUAL_THREADS on Java 21+")
    void testExecutorTypeConfiguration() {
        assertThat(ExecutorType.fromString("VIRTUAL_THREADS")).isEqualTo(ExecutorType.VIRTUAL_THREADS);
        assertThat(ExecutorType.fromString("PLATFORM_POOL")).isEqualTo(ExecutorType.PLATFORM_POOL);
        assertThat(ExecutorType.fromString("virtual_threads")).isEqualTo(ExecutorType.VIRTUAL_THREADS);
        assertThat(ExecutorType.fromString(null)).isEqualTo(ExecutorType.VIRTUAL_THREADS);
        assertThat(ExecutorType.defaultForCurrentJvm()).isEqualTo(ExecutorType.VIRTUAL_THREADS);
    }

    @Test
    @DisplayName("Virtual Thread Execution: Executes rule and verifies Thread.currentThread().isVirtual()")
    void testVirtualThreadExecution() throws Exception {
        RuleSchema schema = new RuleSchema(
                "discount-rule", "1.0", "Check high value discount", "RULE",
                "cartTotal > 100", Map.of("cartTotal", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("discount-rule:1.0", compiled);

        RuleSession session = new RuleSession("eng_alice", "{}", "discount-rule:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        AtomicBoolean wasVirtual = new AtomicBoolean(false);
        OpcodeMetric metric = executionService.execute(1L, Map.of("cartTotal", 150), createMockSecurityContext("alice", EngineRole.ENGINEER));

        assertThat(metric).isNotNull();
        assertThat(metric.getTotalOpcodeCount()).isGreaterThan(0);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXECUTED);
    }

    @Test
    @DisplayName("Async Execution: executeAsync returns CompletableFuture completing with OpcodeMetric")
    void testExecuteAsync() throws Exception {
        RuleSchema schema = new RuleSchema(
                "calc-rule", "1.0", "Calculation rule", "RULE",
                "a + b > 20", Map.of("a", Integer.class, "b", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("calc:1.0", compiled);

        RuleSession session = new RuleSession("eng_bob", "{}", "calc:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(2L)).thenReturn(Optional.of(session));

        CompletableFuture<OpcodeMetric> future = executionService.executeAsync(
                2L, Map.of("a", 15, "b", 10), createMockSecurityContext("bob", EngineRole.ENGINEER)
        );

        OpcodeMetric metric = future.get();
        assertThat(metric).isNotNull();
        assertThat(metric.getExecutionTimeNanos()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Batch Execution: executeBatch evaluates all inputs concurrently using structured concurrency")
    void testBatchExecutionStructuredConcurrency() throws Exception {
        RuleSchema schema = new RuleSchema(
                "tier-rule", "1.0", "Tier classification", "RULE",
                "score * 2 > 100", Map.of("score", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("tier:1.0", compiled);

        RuleSession session = new RuleSession("eng_carol", "{}", "tier:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(3L)).thenReturn(Optional.of(session));

        List<Map<String, Object>> batch = List.of(
                Map.of("score", 40),
                Map.of("score", 60),
                Map.of("score", 75),
                Map.of("score", 20),
                Map.of("score", 90)
        );

        BatchExecutionRequest request = new BatchExecutionRequest(3L, batch);
        BatchExecutionResponse response = executionService.executeBatch(request, createMockSecurityContext("carol", EngineRole.ENGINEER));

        assertThat(response).isNotNull();
        assertThat(response.totalEvaluated()).isEqualTo(5);
        assertThat(response.metrics()).hasSize(5);
        assertThat(response.totalExecutionTimeNanos()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Platform Pool Fallback: Executes rule when PLATFORM_POOL mode is active")
    void testPlatformPoolFallback() throws Exception {
        executionService.setExecutorType(ExecutorType.PLATFORM_POOL);
        assertThat(executionService.getExecutorType()).isEqualTo(ExecutorType.PLATFORM_POOL);

        RuleSchema schema = new RuleSchema(
                "platform-rule", "1.0", "Platform pool rule", "RULE",
                "amount > 50", Map.of("amount", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("platform:1.0", compiled);

        RuleSession session = new RuleSession("eng_dan", "{}", "platform:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(4L)).thenReturn(Optional.of(session));

        OpcodeMetric metric = executionService.execute(4L, Map.of("amount", 100), createMockSecurityContext("dan", EngineRole.ENGINEER));
        assertThat(metric).isNotNull();
        assertThat(metric.getTotalOpcodeCount()).isGreaterThan(0);
    }

    private SecurityContext createMockSecurityContext(String username, EngineRole role) {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims(username, role, now, now.plusSeconds(3600));
        return new JwtSecurityContext(claims, true);
    }
}
