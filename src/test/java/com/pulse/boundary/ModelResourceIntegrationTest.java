package com.pulse.boundary;

import com.pulse.boundary.dto.ModelActionResponse;
import com.pulse.boundary.dto.ModelDetailResponse;
import com.pulse.boundary.dto.ModelSummaryResponse;
import com.pulse.boundary.dto.ModelUploadResponse;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.MlModelRepository;
import com.pulse.control.ModelActivationBroadcaster;
import com.pulse.control.ModelRegistryService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.MlModel;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModelResource REST Integration & RBAC Lifecycle Tests")
class ModelResourceIntegrationTest {

    @Mock
    private MlModelRepository modelRepository;

    @Mock
    private JedisPool jedisPool;

    @Mock
    private Jedis jedis;

    @TempDir
    Path tempStorage;

    private ModelRegistryService registryService;
    private ModelActivationBroadcaster broadcaster;
    private ModelResource resource;

    private final Map<String, MlModel> inMemoryDb = new HashMap<>();

    @BeforeEach
    void setUp() {
        broadcaster = new ModelActivationBroadcaster(jedisPool, "helix:models:activate");
        registryService = new ModelRegistryService(modelRepository, broadcaster, tempStorage.toString(), 25 * 1024 * 1024L);
        resource = new ModelResource(registryService, modelRepository, 25 * 1024 * 1024L);

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

        lenient().when(modelRepository.findAll()).thenAnswer(inv -> new ArrayList<>(inMemoryDb.values()));

        lenient().when(modelRepository.findVersionHistory(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return inMemoryDb.values().stream()
                    .filter(m -> m.getModelName().equals(name))
                    .toList();
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

    private SecurityContext createSecurityContext(String username, EngineRole role) {
        TokenClaims claims = new TokenClaims(username, role, Instant.now(), Instant.now().plusSeconds(3600));
        return new JwtSecurityContext(claims);
    }

    @Test
    @DisplayName("Lifecycle Test: Upload ONNX -> List -> Detail -> Activate -> Delete")
    void testCompleteModelLifecycle() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.publish(anyString(), anyString())).thenReturn(1L);

        SecurityContext scientistCtx = createSecurityContext("dr_smith", EngineRole.DATA_SCIENTIST);
        SecurityContext adminCtx = createSecurityContext("admin_chief", EngineRole.ADMIN);

        // 1. Upload valid ONNX model
        InputStream onnxStream = getClass().getResourceAsStream("/models/ast_reorder_policy.onnx");
        assertThat(onnxStream).isNotNull();

        String schemaJson = "{\"features\":[\"normalized_cost\",\"failure_rate\",\"ratio\",\"is_ml_node\"]}";
        Response uploadResponse = resource.upload(
                onnxStream,
                "ast_reorder_policy.onnx",
                "ast_reorder_policy",
                "1.0.0",
                schemaJson,
                "AST reorder policy model",
                scientistCtx
        );

        assertThat(uploadResponse.getStatus()).isEqualTo(201);
        ModelUploadResponse uploadBody = (ModelUploadResponse) uploadResponse.getEntity();
        assertThat(uploadBody.modelName()).isEqualTo("ast_reorder_policy");
        assertThat(uploadBody.version()).isEqualTo("1.0.0");
        assertThat(uploadBody.location()).isEqualTo("/api/v1/models/ast_reorder_policy");

        // 2. List models
        Response listResponse = resource.listModels();
        assertThat(listResponse.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<ModelSummaryResponse> summaries = (List<ModelSummaryResponse>) listResponse.getEntity();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).modelName()).isEqualTo("ast_reorder_policy");
        assertThat(summaries.get(0).versionCount()).isEqualTo(1);

        // 3. Get model detail
        Response detailResponse = resource.getModelByName("ast_reorder_policy");
        assertThat(detailResponse.getStatus()).isEqualTo(200);
        ModelDetailResponse detailBody = (ModelDetailResponse) detailResponse.getEntity();
        assertThat(detailBody.modelName()).isEqualTo("ast_reorder_policy");
        assertThat(detailBody.versions()).hasSize(1);
        assertThat(detailBody.versions().get(0).uploadedBy()).isEqualTo("dr_smith");

        // 4. Activate version
        Response activateResponse = resource.activateVersion("ast_reorder_policy", "1.0.0", adminCtx);
        assertThat(activateResponse.getStatus()).isEqualTo(200);
        ModelActionResponse activateBody = (ModelActionResponse) activateResponse.getEntity();
        assertThat(activateBody.status()).isEqualTo("SUCCESS");
        verify(jedis).publish(eq("helix:models:activate"), anyString());

        // Verify active state reflects in detail
        Response updatedDetailResponse = resource.getModelByName("ast_reorder_policy");
        ModelDetailResponse updatedDetail = (ModelDetailResponse) updatedDetailResponse.getEntity();
        assertThat(updatedDetail.activeVersion()).isEqualTo("1.0.0");

        // 5. Delete version
        Response deleteResponse = resource.deleteVersion("ast_reorder_policy", "1.0.0", adminCtx);
        assertThat(deleteResponse.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Validation Test: Upload rejects non-.onnx files with 400 Bad Request")
    void testUploadRejectsNonOnnxFile() {
        SecurityContext scientistCtx = createSecurityContext("dr_smith", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("PK...zip content".getBytes(StandardCharsets.UTF_8));

        Response response = resource.upload(
                is,
                "model.zip",
                "fraud_model",
                "1.0.0",
                "{}",
                "zip file",
                scientistCtx
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity().toString()).contains(".onnx extension");
    }

    @Test
    @DisplayName("Validation Test: Upload rejects missing or blank parameters with 400 Bad Request")
    void testUploadRejectsMissingParameters() {
        SecurityContext scientistCtx = createSecurityContext("dr_smith", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("valid_onnx".getBytes(StandardCharsets.UTF_8));

        Response r1 = resource.upload(null, "model.onnx", "model", "1.0.0", "{}", "desc", scientistCtx);
        assertThat(r1.getStatus()).isEqualTo(400);

        Response r2 = resource.upload(is, "model.onnx", "", "1.0.0", "{}", "desc", scientistCtx);
        assertThat(r2.getStatus()).isEqualTo(400);

        Response r3 = resource.upload(is, "model.onnx", "model", "", "{}", "desc", scientistCtx);
        assertThat(r3.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("RBAC Test: Security context role matrix validation")
    void testRbacMatrix() {
        SecurityContext admin = createSecurityContext("admin", EngineRole.ADMIN);
        SecurityContext scientist = createSecurityContext("scientist", EngineRole.DATA_SCIENTIST);
        SecurityContext engineer = createSecurityContext("engineer", EngineRole.ENGINEER);
        SecurityContext operator = createSecurityContext("operator", EngineRole.OPERATOR);

        // Upload allowed for ADMIN and DATA_SCIENTIST
        assertThat(admin.isUserInRole("ADMIN") || admin.isUserInRole("DATA_SCIENTIST")).isTrue();
        assertThat(scientist.isUserInRole("ADMIN") || scientist.isUserInRole("DATA_SCIENTIST")).isTrue();
        assertThat(engineer.isUserInRole("ADMIN") || engineer.isUserInRole("DATA_SCIENTIST")).isFalse();
        assertThat(operator.isUserInRole("ADMIN") || operator.isUserInRole("DATA_SCIENTIST")).isFalse();

        // Activation / deletion allowed only for ADMIN
        assertThat(admin.isUserInRole("ADMIN")).isTrue();
        assertThat(scientist.isUserInRole("ADMIN")).isFalse();
        assertThat(engineer.isUserInRole("ADMIN")).isFalse();
    }
}
