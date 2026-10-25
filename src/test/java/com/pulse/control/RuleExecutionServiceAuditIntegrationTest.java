package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.RuleEngine;
import com.helix.core.HelixEngines;
import com.helix.core.parser.RuleSchema;
import com.pulse.audit.*;
import com.pulse.boundary.dto.BatchExecutionRequest;
import com.pulse.boundary.dto.BatchExecutionResponse;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuleExecutionService and AuditTrailService End-to-End Integration Tests")
class RuleExecutionServiceAuditIntegrationTest {

    private RuleEngine ruleEngine;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private AuditRecordRepository auditRecordRepository;

    @Mock
    private MerkleRootRepository merkleRootRepository;

    private AuditTrailService auditTrailService;
    private RuleExecutionService executionService;
    private List<AuditRecord> persistedRecords;

    @BeforeEach
    void setUp() throws Exception {
        ruleEngine = HelixEngines.createDefault();
        persistedRecords = new CopyOnWriteArrayList<>();

        // Wire real cryptographic primitives for real Merkle chaining in test
        AuditEventSerializer serializer = new AuditEventSerializer();
        MerkleChainAppender appender = new MerkleChainAppender();
        Ed25519Signer signer = new Ed25519Signer();

        lenient().doAnswer(invocation -> {
            AuditRecord record = invocation.getArgument(0);
            persistedRecords.add(record);
            return null;
        }).when(auditRecordRepository).save(any(AuditRecord.class));

        lenient().when(auditRecordRepository.findLatest()).thenAnswer(invocation ->
                persistedRecords.isEmpty() ? Optional.empty() : Optional.of(persistedRecords.get(persistedRecords.size() - 1))
        );

        auditTrailService = new AuditTrailService(
                auditRecordRepository,
                merkleRootRepository,
                serializer,
                appender,
                signer
        );

        executionService = new RuleExecutionService(
                ruleEngine,
                sessionRepository,
                ExecutorType.VIRTUAL_THREADS,
                auditTrailService
        );
    }

    @AfterEach
    void tearDown() {
        if (executionService != null) {
            executionService.destroy();
        }
    }

    @Test
    @DisplayName("Synchronous Execution: Successfully executed rule automatically produces an audit record with PASSED outcome")
    void testSynchronousExecution_auditsPassedOutcome() throws Exception {
        RuleSchema schema = new RuleSchema(
                "LoanEligibility", "1.0.0", "Check loan approval", "RULE",
                "income > 50000 && creditScore >= 700",
                Map.of("income", Integer.class, "creditScore", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("loan:1.0.0", compiled);

        RuleSession session = new RuleSession("tenant-fintech-1", "{}", "loan:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(101L)).thenReturn(Optional.of(session));

        SecurityContext secCtx = createMockSecurityContext("analyst_bob", EngineRole.ENGINEER);

        OpcodeMetric metric = executionService.execute(
                101L,
                Map.of("income", 75000, "creditScore", 740),
                secCtx
        );

        assertThat(metric).isNotNull();
        assertThat(metric.getExecutionTimeNanos()).isGreaterThan(0);

        // Wait for async virtual thread audit persistence
        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.getRuleName()).isEqualTo("LoanEligibility");
        assertThat(record.getRuleVersion()).isEqualTo("1.0.0");
        assertThat(record.getTenantId()).isEqualTo("analyst_bob");
        assertThat(record.getExecutionResult()).isEqualTo("PASSED");
        assertThat(record.getLeafHash()).isNotBlank();
        assertThat(record.getEventHash()).isNotBlank();
        assertThat(record.isSandboxRun()).isFalse();
    }

    @Test
    @DisplayName("Failing Execution: Rule evaluation returning failure captures FAILED session and FAILED audit record")
    void testFailingExecution_auditsFailedOutcome() throws Exception {
        RuleSchema schema = new RuleSchema(
                "DivisionCheck", "1.0.0", "Division by zero check", "RULE",
                "dividend / divisor > 1",
                Map.of("dividend", Integer.class, "divisor", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("div:1.0.0", compiled);

        RuleSession session = new RuleSession("tenant-fintech-2", "{}", "div:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(102L)).thenReturn(Optional.of(session));

        SecurityContext secCtx = createMockSecurityContext("risk_officer", EngineRole.OPERATOR);

        // Division by zero causes ExecutionResult.failure(...)
        OpcodeMetric metric = executionService.execute(
                102L,
                Map.of("dividend", 100, "divisor", 0),
                secCtx
        );

        assertThat(metric).isNotNull();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);

        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.getRuleName()).isEqualTo("DivisionCheck");
        assertThat(record.getExecutionResult()).isEqualTo("FAILED");
        assertThat(record.getTenantId()).isEqualTo("risk_officer");
        assertThat(record.getLeafHash()).isNotBlank();
    }

    @Test
    @DisplayName("Exception Failure: Uncaught exception in execution pipeline records FAILED audit record before rethrowing")
    void testUncaughtExceptionExecution_recordsFailedAuditRecord() throws Exception {
        CompiledRule faultyRule = mock(CompiledRule.class);
        when(faultyRule.getName()).thenReturn("CrashingRule");
        when(faultyRule.getVersion()).thenReturn("1.0.0");

        RuleEngine faultyEngine = mock(RuleEngine.class);
        when(faultyEngine.execute(any(), any())).thenThrow(new IllegalStateException("Fatal engine error"));

        executionService.setRuleEngine(faultyEngine);
        executionService.setExecutorType(ExecutorType.PLATFORM_POOL);
        executionService.cacheCompiledRule("crash:1.0.0", faultyRule);

        RuleSession session = new RuleSession("tenant-fintech-3", "{}", "crash:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(107L)).thenReturn(Optional.of(session));

        SecurityContext secCtx = createMockSecurityContext("crash_tester", EngineRole.ADMIN);

        assertThatThrownBy(() -> executionService.execute(
                107L,
                Map.of(),
                secCtx
        )).isInstanceOf(RuntimeException.class);

        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.getRuleName()).isEqualTo("CrashingRule");
        assertThat(record.getExecutionResult()).isEqualTo("FAILED");
        assertThat(record.getTenantId()).isEqualTo("crash_tester");
    }

    @Test
    @DisplayName("Non-Blocking Virtual Thread Dispatch: Slow audit persistence does not block rule evaluation latency")
    void testAuditExecution_doesNotDegradeRuleEvaluationLatency() throws Exception {
        // Slow audit service simulating 400ms I/O
        AuditTrailService slowAuditService = mock(AuditTrailService.class);
        doAnswer(invocation -> {
            Thread.sleep(400);
            return null;
        }).when(slowAuditService).recordExecution(any(), any(), any(), any(), anyLong(), anyBoolean());

        executionService.setAuditTrailService(slowAuditService);

        RuleSchema schema = new RuleSchema(
                "SpeedTestRule", "1.0.0", "Latency benchmark", "RULE",
                "x + y == 10",
                Map.of("x", Integer.class, "y", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("speed:1.0.0", compiled);

        RuleSession session = new RuleSession("tenant-fast", "{}", "speed:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(103L)).thenReturn(Optional.of(session));

        long start = System.currentTimeMillis();
        OpcodeMetric metric = executionService.execute(
                103L,
                Map.of("x", 4, "y", 6),
                createMockSecurityContext("alice", EngineRole.ENGINEER)
        );
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(metric).isNotNull();
        // Evaluation must return almost instantly (< 100ms) despite 400ms audit lag
        assertThat(elapsedMs).isLessThan(150);

        // Verify async virtual thread eventually invokes recordExecution
        verify(slowAuditService, timeout(2000).times(1))
                .recordExecution(eq("SpeedTestRule"), eq("1.0.0"), eq("alice"), eq("PASSED"), anyLong(), eq(false));
    }

    @Test
    @DisplayName("Asynchronous Execution: executeAsync records audit event upon completion")
    void testAsyncExecution_recordsAuditRecord() throws Exception {
        RuleSchema schema = new RuleSchema(
                "AsyncRule", "2.0.0", "Async evaluation", "RULE",
                "val > 0",
                Map.of("val", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("async:2.0.0", compiled);

        RuleSession session = new RuleSession("tenant-async", "{}", "async:2.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(104L)).thenReturn(Optional.of(session));

        CompletableFuture<OpcodeMetric> future = executionService.executeAsync(
                104L,
                Map.of("val", 42),
                createMockSecurityContext("async_user", EngineRole.ENGINEER)
        );

        OpcodeMetric metric = future.get(5, TimeUnit.SECONDS);
        assertThat(metric).isNotNull();

        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.getRuleName()).isEqualTo("AsyncRule");
        assertThat(record.getRuleVersion()).isEqualTo("2.0.0");
        assertThat(record.getExecutionResult()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("Batch Execution: executeBatch audits all evaluated items")
    void testBatchExecution_recordsAuditForEachItem() throws Exception {
        RuleSchema schema = new RuleSchema(
                "BatchRule", "1.0.0", "Batch evaluation", "RULE",
                "score >= 50",
                Map.of("score", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("batch:1.0.0", compiled);

        RuleSession session = new RuleSession("tenant-batch", "{}", "batch:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(105L)).thenReturn(Optional.of(session));

        List<Map<String, Object>> batch = List.of(
                Map.of("score", 30),
                Map.of("score", 60),
                Map.of("score", 90)
        );

        BatchExecutionRequest request = new BatchExecutionRequest(105L, batch);
        BatchExecutionResponse response = executionService.executeBatch(
                request,
                createMockSecurityContext("batch_user", EngineRole.ENGINEER)
        );

        assertThat(response).isNotNull();
        assertThat(response.totalEvaluated()).isEqualTo(3);

        awaitCondition(() -> persistedRecords.size() >= 3, 3000);

        assertThat(persistedRecords).hasSize(3);
        for (AuditRecord r : persistedRecords) {
            assertThat(r.getRuleName()).isEqualTo("BatchRule");
            assertThat(r.getTenantId()).isEqualTo("batch_user");
            assertThat(r.getLeafHash()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Streaming Execution: executeStream evaluates rule and triggers asynchronous audit")
    void testStreamingExecution_auditsStreamEvent() throws Exception {
        RuleSchema schema = new RuleSchema(
                "StreamTelemetryRule", "1.1.0", "Stream sensor validation", "RULE",
                "temp > 100",
                Map.of("temp", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);

        ExecutionContext ctx = new ExecutionContext(Map.of("temp", 105));
        ExecutionResult result = executionService.executeStream(compiled, ctx, "tenant-iot", false);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();

        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.getRuleName()).isEqualTo("StreamTelemetryRule");
        assertThat(record.getRuleVersion()).isEqualTo("1.1.0");
        assertThat(record.getTenantId()).isEqualTo("tenant-iot");
        assertThat(record.getExecutionResult()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("Sandbox flag propagation: audit record captures sandbox execution flag")
    void testSandboxFlagPropagation() throws Exception {
        RuleSchema schema = new RuleSchema(
                "SandboxRule", "1.0.0", "Untrusted tenant rule", "RULE",
                "creditLimit > 1000",
                Map.of("creditLimit", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("sandbox:1.0.0", compiled);

        RuleSession session = new RuleSession("untrusted-tenant-x", "{}", "sandbox:1.0.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(106L)).thenReturn(Optional.of(session));

        OpcodeMetric metric = executionService.execute(
                106L,
                Map.of("creditLimit", 5000, "__sandbox__", true),
                createMockSecurityContext("untrusted_user", EngineRole.OPERATOR)
        );

        assertThat(metric).isNotNull();

        awaitCondition(() -> !persistedRecords.isEmpty(), 3000);

        assertThat(persistedRecords).hasSize(1);
        AuditRecord record = persistedRecords.get(0);
        assertThat(record.isSandboxRun()).isTrue();
    }

    private void awaitCondition(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition not met within " + timeoutMs + " ms");
    }

    private SecurityContext createMockSecurityContext(String username, EngineRole role) {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims(username, role, now, now.plusSeconds(3600));
        return new JwtSecurityContext(claims, true);
    }
}
