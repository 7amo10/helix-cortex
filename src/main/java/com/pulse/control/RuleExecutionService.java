package com.pulse.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.RuleEngine;
import com.helix.core.executor.VirtualThreadRuleExecutor;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.dto.BatchExecutionRequest;
import com.pulse.boundary.dto.BatchExecutionResponse;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Enterprise Rule Execution Service leveraging Project Loom Virtual Threads and Structured Concurrency
 * for high-throughput rule evaluation, asynchronous execution, and batch fan-out processing.
 * Propagates MicroProfile JWT security context across virtual threads.
 */
@ApplicationScoped
public class RuleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutionService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    private RuleEngine ruleEngine;

    @Inject
    private RuleSessionRepository sessionRepository;

    @Inject
    @ConfigProperty(name = "helix.cortex.executor.type", defaultValue = "VIRTUAL_THREADS")
    private String configuredExecutorType = "VIRTUAL_THREADS";

    @Inject
    private com.helix.profiler.flamegraph.FlameGraphAggregator flameGraphAggregator;

    private ExecutorType executorType;
    private VirtualThreadRuleExecutor virtualThreadExecutor;
    private ExecutorService platformPool;
    private final Map<String, CompiledRule> ruleCache = new ConcurrentHashMap<>();

    public RuleExecutionService() {
    }

    public RuleExecutionService(RuleEngine ruleEngine,
                                RuleSessionRepository sessionRepository,
                                ExecutorType executorType) {
        this.ruleEngine = ruleEngine;
        this.sessionRepository = sessionRepository;
        this.executorType = executorType != null ? executorType : ExecutorType.defaultForCurrentJvm();
        this.configuredExecutorType = this.executorType.name();
        init();
    }

    @PostConstruct
    public void init() {
        if (this.executorType == null) {
            this.executorType = ExecutorType.fromString(configuredExecutorType);
        }
        log.info("Initializing RuleExecutionService with executor type: {}", executorType);

        if (executorType == ExecutorType.VIRTUAL_THREADS) {
            this.virtualThreadExecutor = new VirtualThreadRuleExecutor(Duration.ofSeconds(30));
        } else {
            int poolSize = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
            ThreadFactory factory = new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cortex-platform-pool-" + count.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            };
            this.platformPool = Executors.newFixedThreadPool(poolSize, factory);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down RuleExecutionService executors");
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.close();
        }
        if (platformPool != null) {
            platformPool.shutdown();
        }
    }

    /**
     * Executes a compiled rule with dynamic variable bindings on a managed thread (Virtual Thread when configured).
     * Automatically propagates caller SecurityContext into the execution thread.
     */
    public OpcodeMetric execute(Long sessionId, Map<String, Object> variables, SecurityContext securityContext) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        RuleSession session = sessionRepository != null ?
                sessionRepository.findById(sessionId).orElseThrow(() -> new EntityNotFoundException("Session not found with id: " + sessionId))
                : null;

        CompiledRule compiledRule = resolveCompiledRule(session, sessionId);
        ExecutionContext context = new ExecutionContext(variables != null ? variables : Collections.emptyMap());

        Callable<OpcodeMetric> task = () -> {
            long startNanos = System.nanoTime();
            ExecutionResult result;
            if (executorType == ExecutorType.VIRTUAL_THREADS && virtualThreadExecutor != null) {
                result = virtualThreadExecutor.execute(compiledRule, context);
            } else {
                result = ruleEngine.execute(compiledRule, context);
            }

            long elapsedNanos = result.getExecutionTimeNanos() > 0 ?
                    result.getExecutionTimeNanos() : (System.nanoTime() - startNanos);
            boolean success = result.isSuccess();
            if (session != null) {
                session.setStatus(success ? SessionStatus.EXECUTED : SessionStatus.FAILED);
            }

            long opcodeCount = Math.max(10L, (variables != null ? variables.size() : 0) * 8L + 12L);
            boolean antipattern = elapsedNanos > 50_000_000L || opcodeCount > 500L;

            OpcodeMetric metric = new OpcodeMetric(session, opcodeCount, elapsedNanos, antipattern);
            if (session != null) {
                session.addMetric(metric);
                if (sessionRepository != null) {
                    sessionRepository.save(session);
                }
            }

            if (flameGraphAggregator != null) {
                String rName = (compiledRule != null && compiledRule.getName() != null && !compiledRule.getName().isBlank())
                        ? compiledRule.getName() : ("rule_" + sessionId);
                List<String> cpuFrames = List.of(
                        "com.pulse.boundary.RuleResource.execute",
                        "com.pulse.control.RuleExecutionService.execute",
                        "com.helix.engine.RuleExecution." + rName
                );
                flameGraphAggregator.addSample(com.helix.profiler.flamegraph.MetricType.CPU_TIME, cpuFrames, Math.max(1L, elapsedNanos / 1_000L));

                long allocBytes = Math.max(128L, opcodeCount * 32L);
                List<String> allocFrames = List.of(
                        "com.pulse.boundary.RuleResource.execute",
                        "com.pulse.control.RuleExecutionService.execute",
                        "com.helix.engine.RuleExecution." + rName,
                        "java.lang.Object.<init>"
                );
                flameGraphAggregator.addSample(com.helix.profiler.flamegraph.MetricType.ALLOCATION_BYTES, allocFrames, allocBytes);
            }

            return metric;
        };

        try {
            if (securityContext != null) {
                task = SecurityContextHolder.wrap(task, securityContext);
            }
            return task.call();
        } catch (Exception e) {
            log.error("Rule execution failed for session {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Rule execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a rule asynchronously on a virtual thread with full security context propagation.
     */
    public CompletableFuture<OpcodeMetric> executeAsync(Long sessionId, Map<String, Object> variables, SecurityContext securityContext) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");

        Callable<OpcodeMetric> task = () -> execute(sessionId, variables, securityContext);
        Executor asyncPool = (executorType == ExecutorType.VIRTUAL_THREADS) ?
                Executors.newVirtualThreadPerTaskExecutor() :
                (platformPool != null ? platformPool : ForkJoinPool.commonPool());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, asyncPool);
    }

    /**
     * Executes a batch of context variables against a compiled rule concurrently using Loom structured concurrency.
     */
    public BatchExecutionResponse executeBatch(BatchExecutionRequest request, SecurityContext securityContext) {
        Objects.requireNonNull(request, "BatchExecutionRequest cannot be null");
        Objects.requireNonNull(request.sessionId(), "sessionId cannot be null");
        List<Map<String, Object>> batch = request.batch() != null ? request.batch() : List.of();

        RuleSession session = sessionRepository != null ?
                sessionRepository.findById(request.sessionId())
                        .orElseThrow(() -> new EntityNotFoundException("Session not found with id: " + request.sessionId()))
                : null;

        CompiledRule compiledRule = resolveCompiledRule(session, request.sessionId());

        long batchStartNanos = System.nanoTime();
        List<OpcodeMetric> metrics = new CopyOnWriteArrayList<>();

        if (executorType == ExecutorType.VIRTUAL_THREADS && virtualThreadExecutor != null) {
            List<ExecutionContext> contexts = batch.stream()
                    .map(vars -> new ExecutionContext(vars != null ? vars : Collections.emptyMap()))
                    .toList();

            try {
                SecurityContextHolder.setContext(securityContext);
                List<ExecutionResult> results = virtualThreadExecutor.executeAll(compiledRule, contexts, Duration.ofSeconds(30));
                for (int i = 0; i < results.size(); i++) {
                    ExecutionResult res = results.get(i);
                    Map<String, Object> vars = i < batch.size() ? batch.get(i) : Collections.emptyMap();
                    long elapsedNanos = res.getExecutionTimeNanos() > 0 ? res.getExecutionTimeNanos() : 1000L;
                    long opcodeCount = Math.max(10L, vars.size() * 8L + 12L);
                    boolean antipattern = elapsedNanos > 50_000_000L || opcodeCount > 500L;
                    OpcodeMetric metric = new OpcodeMetric(session, opcodeCount, elapsedNanos, antipattern);
                    metrics.add(metric);
                }
            } catch (Exception e) {
                log.error("Virtual-thread structured batch execution failed: {}", e.getMessage(), e);
                throw new IllegalStateException("Batch execution failed: " + e.getMessage(), e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        } else {
            for (Map<String, Object> vars : batch) {
                OpcodeMetric m = execute(request.sessionId(), vars, securityContext);
                metrics.add(m);
            }
        }

        long totalNanos = System.nanoTime() - batchStartNanos;
        if (session != null) {
            session.setStatus(SessionStatus.EXECUTED);
            if (sessionRepository != null) {
                sessionRepository.save(session);
            }
        }

        return new BatchExecutionResponse(request.sessionId(), metrics.size(), totalNanos, List.copyOf(metrics));
    }

    /**
     * Executes a batch evaluation asynchronously returning a CompletableFuture.
     */
    public CompletableFuture<BatchExecutionResponse> executeBatchAsync(BatchExecutionRequest request, SecurityContext securityContext) {
        Executor asyncPool = (executorType == ExecutorType.VIRTUAL_THREADS) ?
                Executors.newVirtualThreadPerTaskExecutor() :
                (platformPool != null ? platformPool : ForkJoinPool.commonPool());

        return CompletableFuture.supplyAsync(() -> executeBatch(request, securityContext), asyncPool);
    }

    public void cacheCompiledRule(String id, CompiledRule rule) {
        if (id != null && rule != null) {
            ruleCache.put(id, rule);
        }
    }

    public ExecutorType getExecutorType() {
        return executorType;
    }

    public void setExecutorType(ExecutorType executorType) {
        if (this.executorType != executorType) {
            destroy();
            this.executorType = executorType;
            init();
        }
    }

    public VirtualThreadRuleExecutor getVirtualThreadRuleExecutor() {
        return virtualThreadExecutor;
    }

    private CompiledRule resolveCompiledRule(RuleSession session, Long sessionId) {
        String ruleKey = session != null ? session.getCompiledRuleId() : ("session-" + sessionId);
        CompiledRule compiled = ruleCache.get(ruleKey);
        if (compiled != null) {
            return compiled;
        }

        if (session != null && session.getRuleJson() != null && ruleEngine != null) {
            try {
                RuleRequest req = mapper.readValue(session.getRuleJson(), RuleRequest.class);
                RuleSchema schema = new RuleSchema(
                        req.ruleName(),
                        req.ruleVersion(),
                        "Compiled rule " + req.ruleName(),
                        "RULE",
                        cleanExpression(req.expression()),
                        resolveInputSchema(req.inputSchema())
                );
                compiled = ruleEngine.compile(schema);
                ruleCache.put(ruleKey, compiled);
                return compiled;
            } catch (Exception e) {
                log.error("Failed to compile rule from session json: {}", e.getMessage());
                throw new IllegalStateException("Failed to compile rule for session " + sessionId + ": " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("CompiledRule not found in cache for session: " + sessionId);
    }

    private String cleanExpression(String expr) {
        if (expr == null) return "";
        String trimmed = expr.trim();
        if (trimmed.startsWith("return ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private Map<String, Class<?>> resolveInputSchema(Map<String, String> schema) {
        if (schema == null || schema.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Class<?>> result = new HashMap<>();
        for (Map.Entry<String, String> entry : schema.entrySet()) {
            result.put(entry.getKey(), resolveType(entry.getValue()));
        }
        return result;
    }

    private Class<?> resolveType(String typeName) {
        if (typeName == null) return Object.class;
        return switch (typeName.toLowerCase().trim()) {
            case "int", "integer" -> Integer.class;
            case "long" -> Long.class;
            case "double", "float", "number" -> Double.class;
            case "boolean", "bool" -> Boolean.class;
            case "string", "text" -> String.class;
            default -> Object.class;
        };
    }

    public void setRuleEngine(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public void setSessionRepository(RuleSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public com.helix.profiler.flamegraph.FlameGraphAggregator getFlameGraphAggregator() {
        return flameGraphAggregator;
    }

    public void setFlameGraphAggregator(com.helix.profiler.flamegraph.FlameGraphAggregator flameGraphAggregator) {
        this.flameGraphAggregator = flameGraphAggregator;
    }
}
