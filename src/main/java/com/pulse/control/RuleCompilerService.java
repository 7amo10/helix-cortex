package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.Rule;
import com.helix.api.RuleCompilationException;
import com.helix.core.RuleCompiler;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.helix.core.cache.l4.RuleKeyHasher;
import com.helix.core.parser.RuleSchema;
import com.pulse.boundary.dto.RuleRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise service responsible for dynamic bytecode rule compilation integrated with
 * L4 Redis distributed caching and local in-memory compiled rule tiering.
 */
@ApplicationScoped
public class RuleCompilerService {

    private static final Logger log = LoggerFactory.getLogger(RuleCompilerService.class);

    @Inject
    private L4RedisRuleCache l4Cache;

    @Inject
    private L4CacheService l4CacheService;

    private final RuleCompiler ruleCompiler;
    private final Map<String, CompiledRule> localCompiledCache = new ConcurrentHashMap<>();

    public RuleCompilerService() {
        this.ruleCompiler = new RuleCompiler(RuleCompiler.GeneratorType.BYTE_BUDDY);
    }

    public RuleCompilerService(L4RedisRuleCache l4Cache, L4CacheService l4CacheService) {
        this.ruleCompiler = new RuleCompiler(RuleCompiler.GeneratorType.BYTE_BUDDY);
        this.l4Cache = l4Cache;
        this.l4CacheService = l4CacheService;
        init();
    }

    @PostConstruct
    public void init() {
        if (l4CacheService != null) {
            l4CacheService.registerInvalidationListener(this::evictLocal);
        }
    }

    /**
     * Compiles a Rule specification into JVM bytecode, consulting L4 distributed cache first.
     *
     * @param rule rule specification
     * @return compiled, executable rule
     * @throws RuleCompilationException if compilation fails
     */
    public CompiledRule compile(Rule rule) throws RuleCompilationException {
        Objects.requireNonNull(rule, "Rule cannot be null");
        String version = rule.getVersion() != null ? rule.getVersion() : "1.0.0";
        String ruleKey = rule.getName() + ":" + version;

        // 1. Check local in-memory compiled cache
        CompiledRule localRule = localCompiledCache.get(ruleKey);
        if (localRule != null) {
            log.debug("Local compiled cache hit for rule '{}'", ruleKey);
            return localRule;
        }

        // 2. Check L4 distributed Redis cache
        String schemaHash = rule.getInputSchema() != null ? String.valueOf(rule.getInputSchema().hashCode()) : "empty";
        String ruleHash = RuleKeyHasher.hashRule(rule.getName() + ":" + version + ":" + schemaHash);

        if (l4Cache != null) {
            var bytecodeOpt = l4Cache.getBytecode(ruleHash);
            if (bytecodeOpt.isPresent()) {
                log.info("L4 distributed cache HIT for rule '{}' (hash: {})", rule.getName(), ruleHash);
            } else {
                log.debug("L4 distributed cache MISS for rule '{}' (hash: {})", rule.getName(), ruleHash);
            }
        }

        // 3. Compile rule
        CompiledRule compiled = ruleCompiler.compile(rule);
        localCompiledCache.put(ruleKey, compiled);

        // 4. Populate L4 distributed cache with compiled bytecode payload
        if (l4Cache != null) {
            byte[] bytecodePayload = rule.getExpression() != null ?
                    rule.getExpression().getBytes(StandardCharsets.UTF_8) : new byte[0];
            l4Cache.putBytecode(ruleHash, rule.getName(), version, bytecodePayload);
        }

        return compiled;
    }

    /**
     * Compiles a RuleRequest DTO into JVM bytecode.
     *
     * @param req rule request specification
     * @return compiled rule
     * @throws RuleCompilationException if compilation fails
     */
    public CompiledRule compile(RuleRequest req) throws RuleCompilationException {
        Objects.requireNonNull(req, "RuleRequest cannot be null");
        Map<String, Class<?>> resolvedSchema = new HashMap<>();
        if (req.inputSchema() != null) {
            for (Map.Entry<String, String> entry : req.inputSchema().entrySet()) {
                resolvedSchema.put(entry.getKey(), resolveType(entry.getValue()));
            }
        }

        RuleSchema schema = new RuleSchema(
                req.ruleName(),
                req.ruleVersion() != null ? req.ruleVersion() : "1.0.0",
                "Compiled rule " + req.ruleName(),
                "RULE",
                req.expression(),
                resolvedSchema
        );

        return compile(schema);
    }

    /**
     * Evicts a rule from the local in-memory cache upon cluster invalidation.
     *
     * @param ruleName rule name or "__ALL__" to clear all
     */
    public void evictLocal(String ruleName) {
        if (ruleName == null || "__ALL__".equals(ruleName)) {
            localCompiledCache.clear();
            log.info("Cleared all rules from local compiled cache");
            return;
        }

        localCompiledCache.entrySet().removeIf(entry ->
                entry.getKey().startsWith(ruleName + ":") || entry.getKey().equals(ruleName));
        log.info("Evicted rule '{}' from local compiled cache", ruleName);
    }

    public Map<String, CompiledRule> getLocalCache() {
        return Collections.unmodifiableMap(localCompiledCache);
    }

    public L4RedisRuleCache getL4Cache() {
        return l4Cache;
    }

    private Class<?> resolveType(String typeStr) {
        if (typeStr == null) {
            return Object.class;
        }
        return switch (typeStr.trim().toLowerCase()) {
            case "int", "integer" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            case "string" -> String.class;
            default -> Object.class;
        };
    }
}
