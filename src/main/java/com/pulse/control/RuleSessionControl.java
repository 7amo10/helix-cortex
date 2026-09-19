package com.pulse.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.RuleEngine;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transactional control bean bridging JAX-RS boundary requests with the Helix RuleEngine runtime.
 * Manages rule compilation, persistent session tracking, execution invocation, and metric capture.
 * Mandatory transactional context guarantees consistency across rule persistence and telemetry.
 */
@ApplicationScoped
@Transactional(TxType.MANDATORY)
public class RuleSessionControl {

    private static final Logger log = LoggerFactory.getLogger(RuleSessionControl.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    private RuleEngine ruleEngine;

    @Inject
    private RuleSessionRepository sessionRepository;

    @Inject
    private RuleExecutionService executionService;

    @Inject
    private RuleCompilerService compilerService;

    @Inject
    private L4CacheService l4CacheService;

    private final Map<String, CompiledRule> compiledRuleCache = new ConcurrentHashMap<>();

    public RuleSessionControl() {
    }

    public RuleSessionControl(RuleEngine ruleEngine, RuleSessionRepository sessionRepository) {
        this(ruleEngine, sessionRepository, null, null);
    }

    public RuleSessionControl(RuleEngine ruleEngine, RuleSessionRepository sessionRepository, RuleExecutionService executionService) {
        this(ruleEngine, sessionRepository, executionService, null);
    }

    public RuleSessionControl(RuleEngine ruleEngine, RuleSessionRepository sessionRepository, RuleExecutionService executionService, RuleCompilerService compilerService) {
        this.ruleEngine = ruleEngine;
        this.sessionRepository = sessionRepository;
        this.executionService = executionService;
        this.compilerService = compilerService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (l4CacheService != null) {
            l4CacheService.registerInvalidationListener(ruleName -> {
                if (ruleName == null || "__ALL__".equals(ruleName)) {
                    compiledRuleCache.clear();
                } else {
                    compiledRuleCache.entrySet().removeIf(e -> e.getKey().startsWith(ruleName + ":") || e.getKey().equals(ruleName));
                }
            });
        }
    }

    /**
     * Compiles the requested rule definition, caches the compiled rule instance,
     * and persists an initial RuleSession in COMPILED status.
     *
     * @param req        rule compilation specification
     * @param engineerId username of engineer submitting rule
     * @return persisted RuleSession
     */
    public RuleSession compileAndSave(RuleRequest req, String engineerId) throws com.helix.api.RuleCompilationException {
        Objects.requireNonNull(req, "RuleRequest cannot be null");
        Objects.requireNonNull(engineerId, "engineerId cannot be null");

        String expression = cleanExpression(req.expression());
        Map<String, Class<?>> schema = resolveInputSchema(req.inputSchema());

        RuleSchema rule = new RuleSchema(
                req.ruleName(),
                req.ruleVersion(),
                "Compiled rule " + req.ruleName(),
                "RULE",
                expression,
                schema
        );

        CompiledRule compiledRule;
        if (compilerService != null) {
            compiledRule = compilerService.compile(rule);
        } else {
            compiledRule = ruleEngine.compile(rule);
        }
        String compiledId = compiledRule.getName() + ":" + compiledRule.getVersion();
        compiledRuleCache.put(compiledId, compiledRule);
        if (executionService != null) {
            executionService.cacheCompiledRule(compiledId, compiledRule);
        }

        String ruleJson = serializeRuleRequest(req);
        RuleSession session = new RuleSession(engineerId, ruleJson, compiledId, SessionStatus.COMPILED);
        return sessionRepository.save(session);
    }

    /**
     * Executes a compiled rule against the provided variable context, persists an OpcodeMetric,
     * and transitions session status to EXECUTED or FAILED.
     *
     * @param sessionId session database identifier
     * @param variables context variable bindings
     * @return persisted OpcodeMetric
     */
    public OpcodeMetric executeAndSave(Long sessionId, Map<String, Object> variables) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");

        if (executionService != null) {
            return executionService.execute(sessionId, variables, SecurityContextHolder.getContext());
        }

        RuleSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Session not found with id: " + sessionId));

        CompiledRule compiledRule = compiledRuleCache.get(session.getCompiledRuleId());
        if (compiledRule == null) {
            try {
                RuleRequest req = mapper.readValue(session.getRuleJson(), RuleRequest.class);
                RuleSchema rule = new RuleSchema(
                        req.ruleName(),
                        req.ruleVersion(),
                        "Compiled rule " + req.ruleName(),
                        "RULE",
                        cleanExpression(req.expression()),
                        resolveInputSchema(req.inputSchema())
                );
                compiledRule = ruleEngine.compile(rule);
                compiledRuleCache.put(session.getCompiledRuleId(), compiledRule);
            } catch (Exception e) {
                log.error("Failed to recompile cached rule for session {}: {}", sessionId, e.getMessage());
                session.setStatus(SessionStatus.FAILED);
                sessionRepository.save(session);
                throw new IllegalStateException("Failed to recompile rule for execution: " + e.getMessage(), e);
            }
        }

        ExecutionContext context = new ExecutionContext(variables != null ? variables : Collections.emptyMap());
        long startNanos = System.nanoTime();
        ExecutionResult result;
        try {
            result = ruleEngine.execute(compiledRule, context);
        } catch (Exception e) {
            log.warn("Rule execution threw exception: {}", e.getMessage());
            result = ExecutionResult.failure(e, System.nanoTime() - startNanos);
        }

        long elapsedNanos = result.getExecutionTimeNanos() > 0 ?
                result.getExecutionTimeNanos() : (System.nanoTime() - startNanos);

        boolean success = result.isSuccess();
        session.setStatus(success ? SessionStatus.EXECUTED : SessionStatus.FAILED);

        long opcodeCount = Math.max(10L, (variables != null ? variables.size() : 0) * 8L + 12L);
        boolean antipattern = elapsedNanos > 50_000_000L || opcodeCount > 500L;

        OpcodeMetric metric = new OpcodeMetric(session, opcodeCount, elapsedNanos, antipattern);
        session.addMetric(metric);
        sessionRepository.save(session);

        return metric;
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

    private String serializeRuleRequest(RuleRequest req) {
        try {
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            return String.format("{\"ruleName\":\"%s\",\"ruleVersion\":\"%s\",\"expression\":\"%s\"}",
                    req.ruleName(), req.ruleVersion(), req.expression());
        }
    }

    public void setRuleEngine(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public void setSessionRepository(RuleSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public void setExecutionService(RuleExecutionService executionService) {
        this.executionService = executionService;
    }
}
