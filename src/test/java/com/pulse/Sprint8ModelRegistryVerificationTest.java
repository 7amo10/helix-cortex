package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.api.ml.OnnxModelDescriptor;
import com.pulse.control.MlModelRepository;
import com.pulse.control.ModelActivationBroadcaster;
import com.pulse.control.ModelRegistryService;
import com.pulse.entity.MlModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 8: ModelRegistryService & Redis Activation Broadcaster Verification")
class Sprint8ModelRegistryVerificationTest {

    @Mock
    private MlModelRepository modelRepository;

    @Mock
    private JedisPool jedisPool;

    @Mock
    private Jedis jedis;

    @TempDir
    Path storeDir;

    private ModelActivationBroadcaster broadcaster;
    private ModelRegistryService registryService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, MlModel> inMemoryDb = new HashMap<>();

    @BeforeEach
    void setUp() {
        broadcaster = new ModelActivationBroadcaster(jedisPool, "helix:models:activate");
        registryService = new ModelRegistryService(modelRepository, broadcaster, storeDir.toString(), 25 * 1024 * 1024L);

        // Setup in-memory simulation for repository methods
        lenient().when(modelRepository.save(any(MlModel.class))).thenAnswer(inv -> {
            MlModel m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (inMemoryDb.size() + 1));
            }
            inMemoryDb.put(m.getModelName() + ":" + m.getVersion(), m);
            return m;
        });

        lenient().when(modelRepository.findByModelNameAndVersion(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + ":" + inv.getArgument(1);
            return Optional.ofNullable(inMemoryDb.get(key));
        });

        lenient().when(modelRepository.existsByModelNameAndVersion(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + ":" + inv.getArgument(1);
            return inMemoryDb.containsKey(key);
        });

        lenient().when(modelRepository.deactivateAllVersions(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            int count = 0;
            for (MlModel m : inMemoryDb.values()) {
                if (m.getModelName().equals(name)) {
                    m.setActive(false);
                    count++;
                }
            }
            return count;
        });
    }

    @Test
    @DisplayName("Verification 1: End-to-end model upload, filesystem atomic storage, and metadata persistence")
    void testEndToEndUploadAndPersistence() throws Exception {
        InputStream onnxStream = getClass().getResourceAsStream("/models/ast_reorder_policy.onnx");
        assertThat(onnxStream).isNotNull();

        String schemaJson = """
                {
                    "features": ["normalized_cost", "failure_rate", "ratio", "is_ml_node"],
                    "outputTensor": "action_logits"
                }
                """;

        MlModel uploaded = registryService.uploadModel(
                "ast_reorder_policy",
                "1.0.0",
                onnxStream,
                schemaJson,
                "data_scientist_1",
                "Reinforcement learning policy network for AST clause reordering"
        );

        assertThat(uploaded).isNotNull();
        assertThat(uploaded.getId()).isNotNull();
        assertThat(uploaded.getModelName()).isEqualTo("ast_reorder_policy");
        assertThat(uploaded.getVersion()).isEqualTo("1.0.0");
        assertThat(uploaded.getFileSizeKb()).isGreaterThan(0);
        assertThat(uploaded.isActive()).isFalse();

        // Verify physical file was written atomically and exists
        Path savedFile = Path.of(uploaded.getFilePath());
        assertThat(Files.exists(savedFile)).isTrue();
        assertThat(savedFile.getFileName().toString()).isEqualTo("model.onnx");
        assertThat(Files.size(savedFile)).isGreaterThan(1000);
    }

    @Test
    @DisplayName("Verification 2: Non-ONNX or corrupted binary is rejected and cleaned up from disk")
    void testCorruptedBinaryValidationAndCleanup() {
        ByteArrayInputStream garbage = new ByteArrayInputStream("NOT_A_VALID_ONNX_MODEL_FILE".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> registryService.uploadModel(
                "corrupt_model",
                "1.0.0",
                garbage,
                "{}",
                "admin",
                "Invalid"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ONNX");

        // Verify no orphaned temporary or destination files remain in store directory
        Path targetDir = storeDir.resolve("corrupt_model").resolve("1.0.0");
        if (Files.exists(targetDir)) {
            assertThat(targetDir.toFile().list()).isEmpty();
        }
    }

    @Test
    @DisplayName("Verification 3: Atomic version activation, prior version deactivation, and Redis Pub/Sub broadcast")
    void testAtomicActivationAndRedisBroadcast() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.publish(anyString(), anyString())).thenReturn(1L);

        // Pre-populate v1 and v2
        MlModel v1 = new MlModel("fraud_model_v1", "1.0.0", "/models/v1.onnx", 128, "{}", "admin", "v1");
        v1.setActive(true);
        inMemoryDb.put("fraud_model_v1:1.0.0", v1);

        MlModel v2 = new MlModel("fraud_model_v1", "2.0.0", "/models/v2.onnx", 135, "{}", "admin", "v2");
        v2.setActive(false);
        inMemoryDb.put("fraud_model_v1:2.0.0", v2);

        // Activate v2
        MlModel activated = registryService.activateVersion("fraud_model_v1", "2.0.0");

        assertThat(activated.getVersion()).isEqualTo("2.0.0");
        assertThat(activated.isActive()).isTrue();
        assertThat(v1.isActive()).isFalse(); // Prior version was deactivated

        // Verify Redis Pub/Sub broadcast
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(topicCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("helix:models:activate");

        try {
            JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
            assertThat(payload.get("modelName").asText()).isEqualTo("fraud_model_v1");
            assertThat(payload.get("activeVersion").asText()).isEqualTo("2.0.0");
            assertThat(payload.get("storagePath").asText()).isEqualTo("/models/v2.onnx");
            assertThat(payload.has("timestamp")).isTrue();
        } catch (Exception e) {
            throw new AssertionError("Invalid JSON payload emitted to Redis: " + payloadCaptor.getValue(), e);
        }
    }

    @Test
    @DisplayName("Verification 4: ModelRegistry SPI resolution maps entity to OnnxModelDescriptor")
    void testModelRegistrySpiResolution() {
        MlModel model = new MlModel(
                "fraud_model_v1",
                "1.0.0",
                "/tmp/models/fraud_model_v1.onnx",
                256,
                "{\"features\": [\"amount\", \"hour_of_day\", \"is_vpn_or_proxy\"]}",
                "data_scientist_1",
                "Production fraud detector"
        );
        model.setActive(true);
        inMemoryDb.put("fraud_model_v1:1.0.0", model);

        when(modelRepository.findActiveByName("fraud_model_v1")).thenReturn(Optional.of(model));

        Optional<OnnxModelDescriptor> descOpt = registryService.resolveModel("fraud_model_v1");
        assertThat(descOpt).isPresent();

        OnnxModelDescriptor desc = descOpt.get();
        assertThat(desc.modelName()).isEqualTo("fraud_model_v1");
        assertThat(desc.version()).isEqualTo("1.0.0");
        assertThat(desc.modelPath()).isEqualTo("/tmp/models/fraud_model_v1.onnx");
        assertThat(desc.inputFeatures()).containsExactly("amount", "hour_of_day", "is_vpn_or_proxy");
        assertThat(desc.active()).isTrue();
        assertThat(desc.outputTensorName()).isEqualTo("probabilities");
        assertThat(desc.outputIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Verification 5: Duplicate version uploads are rejected with collision protection")
    void testDuplicateUploadCollisionProtection() {
        inMemoryDb.put("fraud_model_v1:1.0.0", new MlModel("fraud_model_v1", "1.0.0", "/path", 10, "{}", "u", "d"));

        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[100]);

        assertThatThrownBy(() -> registryService.uploadModel(
                "fraud_model_v1",
                "1.0.0",
                stream,
                "{}",
                "admin",
                "duplicate"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }
}
