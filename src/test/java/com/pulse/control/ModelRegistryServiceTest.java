package com.pulse.control;

import com.helix.api.ml.OnnxModelDescriptor;
import com.pulse.entity.MlModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelRegistryServiceTest {

    @Mock
    private MlModelRepository modelRepository;

    @Mock
    private ModelActivationBroadcaster broadcaster;

    @TempDir
    Path tempDir;

    private ModelRegistryService service;

    @BeforeEach
    void setUp() {
        service = new ModelRegistryService(modelRepository, broadcaster, tempDir.toString(), 25 * 1024 * 1024L);
    }

    @Test
    @DisplayName("uploadModel successfully saves valid ONNX file atomically and persists entity")
    void testUploadModelSuccess() throws Exception {
        InputStream onnxStream = getClass().getResourceAsStream("/models/ast_reorder_policy.onnx");
        assertThat(onnxStream).as("ONNX test artifact must exist on classpath").isNotNull();

        when(modelRepository.existsByModelNameAndVersion("ast_reorder_policy", "1.0.0")).thenReturn(false);
        when(modelRepository.save(any(MlModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String inputSchema = "{\"features\": [\"normalized_cost\", \"failure_rate\"]}";
        MlModel model = service.uploadModel(
                "ast_reorder_policy",
                "1.0.0",
                onnxStream,
                inputSchema,
                "data_scientist_1",
                "Neural AST reordering RL model"
        );

        assertThat(model).isNotNull();
        assertThat(model.getModelName()).isEqualTo("ast_reorder_policy");
        assertThat(model.getVersion()).isEqualTo("1.0.0");
        assertThat(model.getFileSizeKb()).isGreaterThan(0);
        assertThat(model.getUploadedBy()).isEqualTo("data_scientist_1");
        assertThat(Files.exists(Path.of(model.getFilePath()))).isTrue();

        verify(modelRepository).save(any(MlModel.class));
    }

    @Test
    @DisplayName("uploadModel rejects duplicate model version with IllegalArgumentException")
    void testUploadModelDuplicateCollision() {
        when(modelRepository.existsByModelNameAndVersion("fraud_model_v1", "1.0.0")).thenReturn(true);

        ByteArrayInputStream dummyStream = new ByteArrayInputStream("dummy content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadModel(
                "fraud_model_v1",
                "1.0.0",
                dummyStream,
                "{}",
                "admin",
                "Duplicate model test"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(modelRepository, never()).save(any());
    }

    @Test
    @DisplayName("uploadModel rejects corrupted or non-ONNX file content and cleans up storage")
    void testUploadModelCorruptedContent() {
        when(modelRepository.existsByModelNameAndVersion("invalid_model", "1.0.0")).thenReturn(false);

        ByteArrayInputStream invalidStream = new ByteArrayInputStream("this is definitely not a valid ONNX binary file".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.uploadModel(
                "invalid_model",
                "1.0.0",
                invalidStream,
                "{}",
                "admin",
                "Corrupted test"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ONNX");

        // Verify temporary and destination files are deleted
        Path targetDir = tempDir.resolve("invalid_model").resolve("1.0.0");
        if (Files.exists(targetDir)) {
            assertThat(targetDir.toFile().list()).isEmpty();
        }
        verify(modelRepository, never()).save(any());
    }

    @Test
    @DisplayName("uploadModel rejects files exceeding configured max size threshold")
    void testUploadModelExceedsMaxSize() {
        ModelRegistryService smallService = new ModelRegistryService(
                modelRepository, broadcaster, tempDir.toString(), 100L // 100 bytes max
        );

        when(modelRepository.existsByModelNameAndVersion("too_big", "1.0.0")).thenReturn(false);

        byte[] largeData = new byte[200];
        ByteArrayInputStream largeStream = new ByteArrayInputStream(largeData);

        assertThatThrownBy(() -> smallService.uploadModel(
                "too_big",
                "1.0.0",
                largeStream,
                "{}",
                "admin",
                "Size limit test"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum allowed size");
    }

    @Test
    @DisplayName("activateVersion deactivates prior versions, activates target version, and broadcasts event")
    void testActivateVersion() {
        MlModel model = new MlModel(
                "fraud_model_v1",
                "2.0.0",
                "/tmp/models/fraud_model_v1/2.0.0/model.onnx",
                128,
                "{}",
                "admin",
                "Fraud v2"
        );
        model.setActive(false);

        when(modelRepository.findByModelNameAndVersion("fraud_model_v1", "2.0.0"))
                .thenReturn(Optional.of(model));
        when(modelRepository.save(any(MlModel.class))).thenAnswer(inv -> inv.getArgument(0));

        MlModel activated = service.activateVersion("fraud_model_v1", "2.0.0");

        assertThat(activated.isActive()).isTrue();
        verify(modelRepository).deactivateAllVersions("fraud_model_v1");
        verify(modelRepository).save(model);
        verify(broadcaster).broadcastActivation("fraud_model_v1", "2.0.0", model.getFilePath());
    }

    @Test
    @DisplayName("activateVersion throws IllegalArgumentException when model version does not exist")
    void testActivateVersionNotFound() {
        when(modelRepository.findByModelNameAndVersion("fraud_model_v1", "9.9.9"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateVersion("fraud_model_v1", "9.9.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");

        verify(modelRepository, never()).deactivateAllVersions(anyString());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("resolveModel converts active entity to OnnxModelDescriptor for rule engine execution")
    void testResolveModel() {
        MlModel model = new MlModel(
                "fraud_model_v1",
                "1.0.0",
                "/tmp/model.onnx",
                128,
                "{\"features\": [\"amount\", \"country\"]}",
                "admin",
                "Production model"
        );
        model.setActive(true);

        when(modelRepository.findActiveByName("fraud_model_v1")).thenReturn(Optional.of(model));

        Optional<OnnxModelDescriptor> descOpt = service.resolveModel("fraud_model_v1");

        assertThat(descOpt).isPresent();
        OnnxModelDescriptor desc = descOpt.get();
        assertThat(desc.modelName()).isEqualTo("fraud_model_v1");
        assertThat(desc.version()).isEqualTo("1.0.0");
        assertThat(desc.modelPath()).isEqualTo("/tmp/model.onnx");
        assertThat(desc.inputFeatures()).containsExactly("amount", "country");
        assertThat(desc.active()).isTrue();
    }

    @Test
    @DisplayName("listModels returns list of active OnnxModelDescriptor instances")
    void testListModels() {
        MlModel m1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 100, "{\"features\":[\"f1\"]}", "u1", "d1");
        m1.setActive(true);

        when(modelRepository.findAllActiveModels()).thenReturn(List.of(m1));

        List<OnnxModelDescriptor> descriptors = service.listModels();
        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).modelName()).isEqualTo("fraud_model_v1");
    }

    @Test
    @DisplayName("deleteModelVersion removes model from DB and deletes storage file")
    void testDeleteModelVersion() throws Exception {
        Path modelFile = tempDir.resolve("model.onnx");
        Files.writeString(modelFile, "test data");

        MlModel model = new MlModel("fraud_model_v1", "1.0.0", modelFile.toString(), 10, "{}", "admin", "desc");

        when(modelRepository.findByModelNameAndVersion("fraud_model_v1", "1.0.0")).thenReturn(Optional.of(model));

        boolean deleted = service.deleteModelVersion("fraud_model_v1", "1.0.0");

        assertThat(deleted).isTrue();
        assertThat(Files.exists(modelFile)).isFalse();
        verify(modelRepository).delete(model);
    }
}
