package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.Rule;
import com.helix.api.RuleCompilationException;
import com.helix.api.ml.OnnxModelDescriptor;
import com.helix.core.RuleCompiler;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.helix.core.cache.l4.RuleKeyHasher;
import com.helix.core.ml.LocalModelRegistry;
import com.helix.core.ml.OnnxModelExecutor;
import com.helix.core.ml.OnnxSessionPool;
import com.helix.core.parser.RuleSchema;
import com.helix.core.parser.ast.AstBuilder;
import com.helix.core.parser.ast.BinaryOpNode;
import com.helix.core.parser.ast.ExpressionNode;
import com.helix.core.parser.ast.FieldAccessNode;
import com.helix.core.parser.ast.FunctionCallNode;
import com.helix.core.parser.ast.LiteralNode;
import com.helix.core.parser.ast.MethodCallNode;
import com.helix.core.parser.ast.OnnxInferenceNode;
import com.helix.core.parser.ast.UnaryOpNode;
import com.helix.core.parser.ast.VariableNode;
import com.pulse.boundary.dto.RuleRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise service responsible for dynamic bytecode rule compilation integrated with
 * L4 Redis distributed caching, ML-aware AST model resolution, and local compiled rule tiering.
 */
@ApplicationScoped
public class RuleCompilerService {

    private static final Logger log = LoggerFactory.getLogger(RuleCompilerService.class);

    @Inject
    private L4RedisRuleCache l4Cache;

    @Inject
    private L4CacheService l4CacheService;

    @Inject
    private ModelRegistryService modelRegistryService;

    private final RuleCompiler ruleCompiler;
    private final AstBuilder astBuilder;
    private final LocalModelRegistry localModelRegistry;
    private final Map<String, CompiledRule> localCompiledCache = new ConcurrentHashMap<>();
    private volatile OnnxSessionPool onnxSessionPool;

    public RuleCompilerService() {
        this(null, null, null);
    }

    public RuleCompilerService(L4RedisRuleCache l4Cache, L4CacheService l4CacheService) {
        this(l4Cache, l4CacheService, null);
    }

    public RuleCompilerService(L4RedisRuleCache l4Cache, L4CacheService l4CacheService, ModelRegistryService modelRegistryService) {
        this.ruleCompiler = new RuleCompiler(RuleCompiler.GeneratorType.BYTE_BUDDY);
        this.astBuilder = new AstBuilder();
        this.localModelRegistry = new LocalModelRegistry();
        this.l4Cache = l4Cache;
        this.l4CacheService = l4CacheService;
        this.modelRegistryService = modelRegistryService;
        init();
    }

    @PostConstruct
    public void init() {
        if (l4CacheService != null) {
            l4CacheService.registerInvalidationListener(this::evictLocal);
        }
        getOrCreateSessionPool();
    }

    @PreDestroy
    public void destroy() {
        if (onnxSessionPool != null) {
            try {
                onnxSessionPool.close();
            } catch (Exception e) {
                log.warn("Error closing OnnxSessionPool: {}", e.getMessage());
            }
        }
    }

    public synchronized OnnxSessionPool getOrCreateSessionPool() {
        if (onnxSessionPool == null) {
            try {
                this.onnxSessionPool = new OnnxSessionPool(localModelRegistry);
                OnnxModelExecutor.setSessionPool(this.onnxSessionPool);
            } catch (Exception e) {
                log.warn("Could not initialize OnnxSessionPool: {}", e.getMessage());
            }
        }
        return onnxSessionPool;
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

        // 1. Inspect AST for ML() model references, validate against ModelRegistryService,
        // and inject resolved model paths and feature metadata into LocalModelRegistry / schema context
        Rule effectiveRule = resolveMlModelsAndEnrichSchema(rule);

        // 2. Check local in-memory compiled cache
        CompiledRule localRule = localCompiledCache.get(ruleKey);
        if (localRule != null) {
            log.debug("Local compiled cache hit for rule '{}'", ruleKey);
            return localRule;
        }

        // 3. Check L4 distributed Redis cache
        String schemaHash = effectiveRule.getInputSchema() != null ? String.valueOf(effectiveRule.getInputSchema().hashCode()) : "empty";
        String ruleHash = RuleKeyHasher.hashRule(effectiveRule.getName() + ":" + version + ":" + schemaHash);

        if (l4Cache != null) {
            var bytecodeOpt = l4Cache.getBytecode(ruleHash);
            if (bytecodeOpt.isPresent()) {
                log.info("L4 distributed cache HIT for rule '{}' (hash: {})", effectiveRule.getName(), ruleHash);
            } else {
                log.debug("L4 distributed cache MISS for rule '{}' (hash: {})", effectiveRule.getName(), ruleHash);
            }
        }

        // 4. Compile rule
        CompiledRule compiled = ruleCompiler.compile(effectiveRule);
        localCompiledCache.put(ruleKey, compiled);

        // 5. Populate L4 distributed cache with compiled bytecode payload
        if (l4Cache != null) {
            byte[] bytecodePayload = effectiveRule.getExpression() != null ?
                    effectiveRule.getExpression().getBytes(StandardCharsets.UTF_8) : new byte[0];
            l4Cache.putBytecode(ruleHash, effectiveRule.getName(), version, bytecodePayload);
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
     * Inspects AST expressions for ML() calls, queries ModelRegistryService for active versions,
     * injects descriptors into LocalModelRegistry and ensures OnnxSessionPool is initialized,
     * and enriches the input schema with model feature definitions.
     *
     * @param rule incoming rule specification
     * @return enriched rule specification with validated ML dependencies
     * @throws RuleCompilationException if a referenced ML model does not exist or has no active version
     */
    public Rule resolveMlModelsAndEnrichSchema(Rule rule) throws RuleCompilationException {
        String expression = rule.getExpression();
        if (expression == null || expression.isBlank()) {
            return rule;
        }

        Set<String> modelNames = extractModelReferences(expression);
        if (modelNames.isEmpty()) {
            return rule;
        }

        Map<String, Class<?>> enrichedSchema = new HashMap<>(
                rule.getInputSchema() != null ? rule.getInputSchema() : Collections.emptyMap()
        );

        for (String modelName : modelNames) {
            OnnxModelDescriptor descriptor = null;

            // 1. Query ModelRegistryService as primary enterprise source of truth
            if (modelRegistryService != null) {
                descriptor = modelRegistryService.resolveModel(modelName).orElse(null);
            } else {
                // Fall back to local in-process registry if modelRegistryService is not wired
                descriptor = localModelRegistry.resolveModel(modelName).orElse(null);
            }

            // 2. Fail compilation if referenced model does not exist or has no active version
            if (descriptor == null || !descriptor.active()) {
                throw new RuleCompilationException(
                        "Referenced ML model '" + modelName + "' does not exist or has no active version in the model registry"
                );
            }

            // 4. Inject resolved model descriptor into LocalModelRegistry
            localModelRegistry.registerModel(descriptor);

            // Ensure OnnxSessionPool is initialized and synchronized
            OnnxSessionPool pool = getOrCreateSessionPool();
            if (pool != null) {
                pool.invalidate(modelName);
            }

            // 5. Inject feature metadata into compilation context schema
            if (descriptor.inputFeatures() != null) {
                for (String feature : descriptor.inputFeatures()) {
                    enrichedSchema.putIfAbsent(feature, Double.class);
                }
            }

            log.info("Resolved ML model '{}' v{} (path: {}) with {} features for rule '{}'",
                    descriptor.modelName(), descriptor.version(), descriptor.modelPath(),
                    descriptor.inputFeatures() != null ? descriptor.inputFeatures().size() : 0,
                    rule.getName());
        }

        return new RuleSchema(
                rule.getName(),
                rule.getVersion(),
                rule.getDescription(),
                "RULE",
                rule.getExpression(),
                enrichedSchema
        );
    }

    /**
     * Extracts unique model names referenced by ML() calls in the expression AST.
     *
     * @param expression raw rule expression
     * @return set of referenced model names
     * @throws RuleCompilationException if expression parsing fails
     */
    public Set<String> extractModelReferences(String expression) throws RuleCompilationException {
        if (expression == null || expression.isBlank()) {
            return Collections.emptySet();
        }
        if (!expression.contains("ML(") && !expression.contains("ML (") && !expression.toLowerCase().contains("ml(")) {
            return Collections.emptySet();
        }

        try {
            ExpressionNode astRoot = astBuilder.buildAst(expression);
            Set<String> modelNames = new LinkedHashSet<>();
            collectModelNames(astRoot, modelNames);
            return Collections.unmodifiableSet(modelNames);
        } catch (Exception e) {
            throw new RuleCompilationException("Failed to inspect AST for ML references in expression: " + e.getMessage(), e);
        }
    }

    private void collectModelNames(ExpressionNode node, Set<String> modelNames) {
        if (node == null) {
            return;
        }
        if (node instanceof OnnxInferenceNode onnxNode) {
            modelNames.add(onnxNode.getModelName());
        } else if (node instanceof BinaryOpNode binaryOpNode) {
            collectModelNames(binaryOpNode.getLeft(), modelNames);
            collectModelNames(binaryOpNode.getRight(), modelNames);
        } else if (node instanceof UnaryOpNode unaryOpNode) {
            collectModelNames(unaryOpNode.getOperand(), modelNames);
        } else if (node instanceof FunctionCallNode func) {
            if ("ML".equalsIgnoreCase(func.getFunctionName()) && func.getArguments() != null && !func.getArguments().isEmpty()) {
                ExpressionNode arg0 = func.getArguments().get(0);
                if (arg0 instanceof LiteralNode lit && lit.getValue() instanceof String s) {
                    modelNames.add(s);
                } else if (arg0 instanceof VariableNode var) {
                    modelNames.add(var.getName());
                }
            }
            if (func.getArguments() != null) {
                for (ExpressionNode arg : func.getArguments()) {
                    collectModelNames(arg, modelNames);
                }
            }
        } else if (node instanceof MethodCallNode methodCall) {
            collectModelNames(methodCall.getTarget(), modelNames);
            if (methodCall.getArguments() != null) {
                for (ExpressionNode arg : methodCall.getArguments()) {
                    collectModelNames(arg, modelNames);
                }
            }
        } else if (node instanceof FieldAccessNode fieldAccess) {
            collectModelNames(fieldAccess.getTarget(), modelNames);
        }
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

    public LocalModelRegistry getLocalModelRegistry() {
        return localModelRegistry;
    }

    public OnnxSessionPool getOnnxSessionPool() {
        return onnxSessionPool;
    }

    public ModelRegistryService getModelRegistryService() {
        return modelRegistryService;
    }

    public void setModelRegistryService(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
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
