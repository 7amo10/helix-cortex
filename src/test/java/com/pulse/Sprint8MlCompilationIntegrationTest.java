package com.pulse;

import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.RuleCompilationException;
import com.helix.api.RuleEngine;
import com.helix.core.RuleCompiler;
import com.helix.core.ml.OnnxModelExecutor;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.control.L4CacheService;
import com.pulse.control.MlModelRepository;
import com.pulse.control.ModelActivationBroadcaster;
import com.pulse.control.ModelRegistryService;
import com.pulse.control.RuleCompilerService;
import com.pulse.control.RuleExecutionService;
import com.pulse.control.RuleSessionControl;
import com.pulse.control.RuleSessionRepository;
import com.pulse.entity.MlModel;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 8: ML-Aware Compilation Pipeline End-to-End Integration Verification")
class Sprint8MlCompilationIntegrationTest {

    @Mock
    private MlModelRepository modelRepository;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private JedisPool jedisPool;

    @Mock
    private Jedis jedis;

    @TempDir
    Path modelStoreDir;

    private L4CacheService cacheService;
    private ModelActivationBroadcaster broadcaster;
    private ModelRegistryService modelRegistryService;
    private RuleCompilerService compilerService;
    private RuleExecutionService executionService;
    private RuleSessionControl sessionControl;
    private final Map<String, MlModel> inMemoryModels = new HashMap<>();
    private final Map<Long, RuleSession> inMemorySessions = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Wire in-memory database simulation for MlModelRepository
        lenient().when(modelRepository.save(any(MlModel.class))).thenAnswer(inv -> {
            MlModel m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (inMemoryModels.size() + 1));
            }
            inMemoryModels.put(m.getModelName() + ":" + m.getVersion(), m);
            return m;
        });

        lenient().when(modelRepository.findByModelNameAndVersion(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + ":" + inv.getArgument(1);
            return Optional.ofNullable(inMemoryModels.get(key));
        });

        lenient().when(modelRepository.findActiveByName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return inMemoryModels.values().stream()
                    .filter(m -> m.getModelName().equals(name) && m.isActive())
                    .findFirst();
        });

        lenient().when(modelRepository.existsByModelNameAndVersion(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + ":" + inv.getArgument(1);
            return inMemoryModels.containsKey(key);
        });

        lenient().when(modelRepository.deactivateAllVersions(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            int count = 0;
            for (MlModel m : inMemoryModels.values()) {
                if (m.getModelName().equals(name)) {
                    m.setActive(false);
                    count++;
                }
            }
            return count;
        });

        // Wire in-memory database simulation for RuleSessionRepository
        lenient().when(sessionRepository.save(any(RuleSession.class))).thenAnswer(inv -> {
            RuleSession s = inv.getArgument(0);
            if (s.getId() == null) {
                try {
                    var idField = RuleSession.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(s, (long) (inMemorySessions.size() + 1));
                } catch (Exception ignored) {
                }
            }
            inMemorySessions.put(s.getId(), s);
            return s;
        });

        lenient().when(sessionRepository.findById(any(Long.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(inMemorySessions.get(id));
        });

        // Wire Redis mocks for activation broadcast
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        lenient().when(jedis.publish(anyString(), anyString())).thenReturn(1L);

        broadcaster = new ModelActivationBroadcaster(jedisPool, "helix:models:activate");
        modelRegistryService = new ModelRegistryService(
                modelRepository,
                broadcaster,
                modelStoreDir.toString(),
                25 * 1024 * 1024L
        );

        cacheService = new L4CacheService("localhost", 6379, 2000, true);
        compilerService = new RuleCompilerService(cacheService.getCache(), cacheService, modelRegistryService);

        RuleEngine ruleEngine = new com.helix.core.DefaultRuleEngine();
        executionService = new RuleExecutionService(ruleEngine, sessionRepository, com.pulse.control.ExecutorType.VIRTUAL_THREADS);
        executionService.setCompilerService(compilerService);

        sessionControl = new RuleSessionControl(ruleEngine, sessionRepository, executionService, compilerService);
    }

    @AfterEach
    void tearDown() {
        if (compilerService != null) {
            compilerService.destroy();
        }
        if (executionService != null) {
            executionService.destroy();
        }
        if (cacheService != null) {
            cacheService.destroy();
        }
        OnnxModelExecutor.reset();
    }

    @Test
    @DisplayName("Verification 1: Unregistered ML model reference fails compilation with clear diagnostic")
    void testUnregisteredModelCompilationFails() {
        RuleRequest request = new RuleRequest(
                "FraudCheckRule",
                "1.0.0",
                "amount > 1000 && ML(unregistered_fraud_detector) > 0.85",
                Map.of("amount", "double")
        );

        assertThatThrownBy(() -> sessionControl.compileAndSave(request, "lead_engineer"))
                .isInstanceOf(RuleCompilationException.class)
                .hasMessageContaining("unregistered_fraud_detector")
                .hasMessageContaining("does not exist or has no active version");
    }

    @Test
    @DisplayName("Verification 2: End-to-end model upload, activation, rule compilation, and real ONNX execution")
    void testEndToEndModelUploadCompilationAndExecution() throws Exception {
        // Step 1: Upload real ONNX fraud classifier model
        InputStream onnxStream = getClass().getResourceAsStream("/models/fraud_model_v1.onnx");
        assertThat(onnxStream).isNotNull();

        String schemaJson = """
                {
                    "features": [
                        "amount", "hour_of_day", "day_of_week", "merchant_category", "distance_from_home",
                        "velocity_1h", "velocity_24h", "velocity_7d", "amount_deviation_30d", "unique_merchants_24h",
                        "is_new_device", "device_risk_score", "is_vpn_or_proxy", "country_mismatch",
                        "account_age_days", "is_account_suspended", "previous_chargeback"
                    ],
                    "outputTensor": "probabilities",
                    "outputIndex": 1
                }
                """;

        MlModel uploaded = modelRegistryService.uploadModel(
                "fraud_model_v1",
                "1.0.0",
                onnxStream,
                schemaJson,
                "data_scientist_1",
                "Production fraud detector"
        );
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.isActive()).isFalse();

        // Step 2: Activate model version
        MlModel activated = modelRegistryService.activateVersion("fraud_model_v1", "1.0.0");
        assertThat(activated.isActive()).isTrue();

        // Step 3: Compile rule referencing the active model
        RuleRequest ruleReq = new RuleRequest(
                "FraudScreeningRule",
                "1.0.0",
                "amount > 500.0 && ML(fraud_model_v1) >= 0.0",
                Map.of("amount", "double")
        );

        RuleSession session = sessionControl.compileAndSave(ruleReq, "lead_engineer");
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotNull();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPILED);

        // Verify model was injected into LocalModelRegistry with active state
        var resolvedModel = compilerService.getLocalModelRegistry().resolveModel("fraud_model_v1");
        assertThat(resolvedModel).isPresent();
        assertThat(resolvedModel.get().version()).isEqualTo("1.0.0");
        assertThat(resolvedModel.get().active()).isTrue();
        assertThat(resolvedModel.get().inputFeatures()).hasSize(17);

        // Step 4: Execute compiled rule with real ONNX inference inputs
        Map<String, Object> inputVariables = Map.ofEntries(
                Map.entry("amount", 1200.0),
                Map.entry("hour_of_day", 14.0),
                Map.entry("day_of_week", 3.0),
                Map.entry("merchant_category", 5411.0),
                Map.entry("distance_from_home", 2.5),
                Map.entry("velocity_1h", 1.0),
                Map.entry("velocity_24h", 3.0),
                Map.entry("velocity_7d", 10.0),
                Map.entry("amount_deviation_30d", 1.2),
                Map.entry("unique_merchants_24h", 2.0),
                Map.entry("is_new_device", 0.0),
                Map.entry("device_risk_score", 0.1),
                Map.entry("is_vpn_or_proxy", 0.0),
                Map.entry("country_mismatch", 0.0),
                Map.entry("account_age_days", 365.0),
                Map.entry("is_account_suspended", 0.0),
                Map.entry("previous_chargeback", 0.0)
        );

        OpcodeMetric metric = sessionControl.executeAndSave(session.getId(), inputVariables);
        assertThat(metric).isNotNull();
        assertThat(metric.getExecutionTimeNanos()).isGreaterThan(0L);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXECUTED);

        // Step 5: Verify direct bytecode evaluation against ExecutionContext
        CompiledRule cachedRule = compilerService.getLocalCache().get("FraudScreeningRule:1.0.0");
        assertThat(cachedRule).isNotNull();
        ExecutionContext ctx = new ExecutionContext(inputVariables);
        ExecutionResult directResult = cachedRule.execute(ctx);
        assertThat(directResult.isSuccess()).isTrue();
        assertThat(directResult.getResult()).contains(Boolean.TRUE);
    }

    @Test
    @DisplayName("Verification 3: Inactive model version fails compilation until explicitly activated")
    void testInactiveModelVersionFailsUntilActivated() throws Exception {
        InputStream onnxStream = getClass().getResourceAsStream("/models/ast_reorder_policy.onnx");
        assertThat(onnxStream).isNotNull();

        modelRegistryService.uploadModel(
                "staged_model",
                "1.0.0",
                onnxStream,
                "{\"features\": [\"normalized_cost\", \"failure_rate\", \"ratio\", \"is_ml_node\"]}",
                "admin",
                "Uploaded but inactive"
        );

        RuleRequest ruleReq = new RuleRequest(
                "StagedModelRule",
                "1.0.0",
                "ML(staged_model) > 0.5",
                Map.of("normalized_cost", "double")
        );

        // Attempting to compile while inactive should fail
        assertThatThrownBy(() -> sessionControl.compileAndSave(ruleReq, "engineer_1"))
                .isInstanceOf(RuleCompilationException.class)
                .hasMessageContaining("staged_model")
                .hasMessageContaining("does not exist or has no active version");

        // Activate model version
        modelRegistryService.activateVersion("staged_model", "1.0.0");

        // Now compilation must succeed
        RuleSession session = sessionControl.compileAndSave(ruleReq, "engineer_1");
        assertThat(session).isNotNull();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPILED);
    }
}
