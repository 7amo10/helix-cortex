package com.pulse.control;

import com.helix.core.HelixEngines;
import com.helix.api.CompiledRule;
import com.helix.api.RuleEngine;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.RuleResource;
import com.pulse.boundary.dto.ExecutionRequest;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.entity.EngineRole;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Task 5.1 & AC 1: High-Concurrency Rule Execution Load Test (1,000+ Requests)")
class HighConcurrencyRuleExecutionLoadTest {

    private RuleEngine ruleEngine;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private RuleSessionControl control;

    private RuleExecutionService executionService;
    private RuleResource ruleResource;

    @BeforeEach
    void setUp() throws Exception {
        ruleEngine = HelixEngines.createDefault();
        executionService = new RuleExecutionService(ruleEngine, sessionRepository, ExecutorType.VIRTUAL_THREADS);

        RuleSchema schema = new RuleSchema(
                "high-concurrency-rule",
                "1.0",
                "High concurrency load testing rule",
                "RULE",
                "(a * b) + c > 100",
                Map.of("a", Integer.class, "b", Integer.class, "c", Integer.class)
        );
        CompiledRule compiled = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("high-concurrency-rule:1.0", compiled);

        RuleSession session = new RuleSession("load_tester", "{}", "high-concurrency-rule:1.0", SessionStatus.COMPILED);
        lenient().when(sessionRepository.findById(100L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.save(any(RuleSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ruleResource = new RuleResource(control, sessionRepository, createMockSecurityContext("load_tester", EngineRole.ENGINEER), executionService);
    }

    @AfterEach
    void tearDown() {
        if (executionService != null) {
            executionService.destroy();
        }
    }

    @Test
    @DisplayName("Load Test: 1,000+ concurrent requests against POST /api/v1/rules/execute show zero timeouts and stable memory")
    void testHighConcurrencyLoad1000Requests() throws Exception {
        int requestCount = 1000;
        ExecutorService clientPool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(requestCount);

        List<Future<?>> futures = new ArrayList<>(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        System.gc();
        long initialHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long startTime = System.nanoTime();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            futures.add(clientPool.submit(() -> {
                try {
                    startSignal.await(5, TimeUnit.SECONDS);
                    Map<String, Object> vars = Map.of(
                            "a", 10 + (index % 10),
                            "b", 12,
                            "c", index % 5
                    );
                    SecurityContext ctx = createMockSecurityContext("worker_" + index, EngineRole.ENGINEER);
                    OpcodeMetric metric = executionService.execute(100L, vars, ctx);
                    successCount.incrementAndGet();
                    return metric;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out")) {
                        timeoutCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                    throw new RuntimeException(e);
                } finally {
                    doneSignal.countDown();
                }
            }));
        }

        // Release all 1,000 tasks concurrently
        startSignal.countDown();
        boolean completed = doneSignal.await(30, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startTime;
        long durationMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        long finalHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapGrowthBytes = Math.max(0, finalHeapUsed - initialHeapUsed);
        double heapGrowthMB = heapGrowthBytes / (1024.0 * 1024.0);

        // Verification & Acceptance Criteria Assertions
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(requestCount);
        assertThat(timeoutCount.get()).isZero();
        assertThat(errorCount.get()).isZero();

        // Stable memory footprint: 1,000 virtual thread executions must not cause excessive heap bloat (< 64 MB growth)
        assertThat(heapGrowthMB).isLessThan(64.0);

        // Average execution throughput
        double opsPerSec = (requestCount * 1000.0) / Math.max(1, durationMs);
        assertThat(opsPerSec).isGreaterThan(50.0);

        clientPool.shutdown();
    }

    @Test
    @DisplayName("Load Test: Direct REST endpoint POST /api/v1/rules/execute with 1,000 concurrent requests")
    void testRestEndpointDirectLoad1000Requests() throws Exception {
        int requestCount = 1000;
        ExecutorService clientPool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(requestCount);

        AtomicInteger success200Count = new AtomicInteger(0);
        lenient().when(control.executeAndSave(any(), any())).thenAnswer(inv -> {
            Long sid = inv.getArgument(0);
            Map<String, Object> vars = inv.getArgument(1);
            return executionService.execute(sid, vars, null);
        });

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            clientPool.submit(() -> {
                try {
                    startSignal.await();
                    ExecutionRequest req = new ExecutionRequest(100L, Map.of("a", 10, "b", 15, "c", index % 2));
                    Response resp = ruleResource.executeDirect(req, null);
                    if (resp.getStatus() == 200) {
                        success200Count.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        boolean completed = doneSignal.await(30, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(success200Count.get()).isEqualTo(requestCount);
        clientPool.shutdown();
    }

    private SecurityContext createMockSecurityContext(String username, EngineRole role) {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims(username, role, now, now.plusSeconds(3600));
        return new JwtSecurityContext(claims, true);
    }
}
