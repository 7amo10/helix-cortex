package com.pulse.boundary;

import com.pulse.boundary.dto.ModelActionResponse;
import com.pulse.boundary.dto.ModelDetailResponse;
import com.pulse.boundary.dto.ModelSummaryResponse;
import com.pulse.boundary.dto.ModelUploadResponse;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.Secured;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.MlModelRepository;
import com.pulse.control.ModelRegistryService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.MlModel;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelResourceTest {

    @Mock
    private ModelRegistryService registryService;

    @Mock
    private MlModelRepository modelRepository;

    private ModelResource resource;

    @BeforeEach
    void setUp() {
        resource = new ModelResource(registryService, modelRepository, 25 * 1024 * 1024L);
    }

    private SecurityContext createSecurityContext(String user, EngineRole role) {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims(user, role, now, now.plusSeconds(3600));
        return new JwtSecurityContext(claims);
    }

    @Test
    @DisplayName("GET /models returns 200 OK with list of model family summaries")
    void testListModels() {
        MlModel m1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 128, "{}", "u1", "desc1");
        m1.setActive(true);
        MlModel m2 = new MlModel("ast_reorder_policy", "1.0.0", "/path2", 225, "{}", "u2", "desc2");
        m2.setActive(true);

        when(modelRepository.findAll()).thenReturn(List.of(m1, m2));

        Response response = resource.listModels();
        assertThat(response.getStatus()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        List<ModelSummaryResponse> summaries = (List<ModelSummaryResponse>) response.getEntity();
        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(ModelSummaryResponse::modelName)
                .containsExactlyInAnyOrder("fraud_model_v1", "ast_reorder_policy");
    }

    @Test
    @DisplayName("GET /models/{name} returns 200 OK with model detail and version history")
    void testGetModelByNameFound() {
        MlModel v1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 120, "{}", "u1", "v1");
        MlModel v2 = new MlModel("fraud_model_v1", "2.0.0", "/path2", 135, "{}", "u1", "v2");
        v2.setActive(true);

        when(modelRepository.findVersionHistory("fraud_model_v1")).thenReturn(List.of(v2, v1));

        Response response = resource.getModelByName("fraud_model_v1");
        assertThat(response.getStatus()).isEqualTo(200);

        ModelDetailResponse detail = (ModelDetailResponse) response.getEntity();
        assertThat(detail.modelName()).isEqualTo("fraud_model_v1");
        assertThat(detail.activeVersion()).isEqualTo("2.0.0");
        assertThat(detail.versionCount()).isEqualTo(2);
        assertThat(detail.versions()).hasSize(2);
    }

    @Test
    @DisplayName("GET /models/{name} returns 404 Not Found when model does not exist")
    void testGetModelByNameNotFound() {
        when(modelRepository.findVersionHistory("unknown_model")).thenReturn(Collections.emptyList());

        Response response = resource.getModelByName("unknown_model");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /models/{name} returns 400 Bad Request when name is blank")
    void testGetModelByNameBlank() {
        Response response = resource.getModelByName("   ");
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /models rejects upload if file does not have .onnx extension")
    void testUploadModelInvalidExtension() {
        SecurityContext sc = createSecurityContext("scientist_1", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("dummy".getBytes(StandardCharsets.UTF_8));

        Response response = resource.upload(
                is,
                "model.pt", // PyTorch extension instead of .onnx
                "fraud_model",
                "1.0.0",
                "{}",
                "Test",
                sc
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity().toString()).contains(".onnx extension");
        verifyNoInteractions(registryService);
    }

    @Test
    @DisplayName("POST /models rejects upload if file size exceeds 25 MB limit")
    void testUploadModelExceedsSizeLimit() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);
        ModelResource smallResource = new ModelResource(registryService, modelRepository, 100L); // 100 bytes limit

        byte[] large = new byte[200];
        ByteArrayInputStream is = new ByteArrayInputStream(large);

        Response response = smallResource.upload(
                is,
                "model.onnx",
                "fraud_model",
                "1.0.0",
                "{}",
                "Test",
                sc
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity().toString()).contains("exceeds maximum allowed limit");
        verifyNoInteractions(registryService);
    }

    @Test
    @DisplayName("POST /models rejects upload with missing model name or version")
    void testUploadModelMissingFields() {
        SecurityContext sc = createSecurityContext("scientist_1", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("dummy".getBytes(StandardCharsets.UTF_8));

        Response r1 = resource.upload(is, "model.onnx", "", "1.0.0", "{}", "desc", sc);
        assertThat(r1.getStatus()).isEqualTo(400);

        Response r2 = resource.upload(is, "model.onnx", "model", "  ", "{}", "desc", sc);
        assertThat(r2.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /models successfully registers model and returns 201 Created with Location header")
    void testUploadModelSuccess() {
        SecurityContext sc = createSecurityContext("scientist_1", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("valid_onnx_bytes".getBytes(StandardCharsets.UTF_8));

        MlModel saved = new MlModel(
                "fraud_model_v1",
                "1.0.0",
                "/var/helix/models/fraud_model_v1/1.0.0/model.onnx",
                256,
                "{}",
                "scientist_1",
                "desc"
        );

        when(registryService.uploadModel(eq("fraud_model_v1"), eq("1.0.0"), any(InputStream.class), anyString(), eq("scientist_1"), anyString()))
                .thenReturn(saved);

        Response response = resource.upload(
                is,
                "model.onnx",
                "fraud_model_v1",
                "1.0.0",
                "{}",
                "desc",
                sc
        );

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getLocation()).isNotNull();
        assertThat(response.getLocation().getPath()).isEqualTo("/api/v1/models/fraud_model_v1");

        ModelUploadResponse body = (ModelUploadResponse) response.getEntity();
        assertThat(body.modelName()).isEqualTo("fraud_model_v1");
        assertThat(body.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("POST /models returns 400 Bad Request when model version collision occurs")
    void testUploadModelCollision() {
        SecurityContext sc = createSecurityContext("scientist_1", EngineRole.DATA_SCIENTIST);
        ByteArrayInputStream is = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

        when(registryService.uploadModel(anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Model 'fraud_model_v1' version '1.0.0' already exists"));

        Response response = resource.upload(
                is,
                "model.onnx",
                "fraud_model_v1",
                "1.0.0",
                "{}",
                "desc",
                sc
        );

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity().toString()).contains("already exists");
    }

    @Test
    @DisplayName("PUT /models/{name}/activate activates model version and returns 200 OK")
    void testActivateVersionSuccess() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);
        MlModel activated = new MlModel("fraud_model_v1", "2.0.0", "/path", 128, "{}", "admin", "desc");
        activated.setActive(true);

        when(registryService.activateVersion("fraud_model_v1", "2.0.0")).thenReturn(activated);

        Response response = resource.activateVersion("fraud_model_v1", "2.0.0", sc);
        assertThat(response.getStatus()).isEqualTo(200);

        ModelActionResponse body = (ModelActionResponse) response.getEntity();
        assertThat(body.status()).isEqualTo("SUCCESS");
        assertThat(body.modelName()).isEqualTo("fraud_model_v1");
        assertThat(body.version()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("PUT /models/{name}/activate returns 404 when model or version is not found")
    void testActivateVersionNotFound() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);

        when(registryService.activateVersion("fraud_model_v1", "9.9.9"))
                .thenThrow(new IllegalArgumentException("Model 'fraud_model_v1' version '9.9.9' not found"));

        Response response = resource.activateVersion("fraud_model_v1", "9.9.9", sc);
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("PUT /models/{name}/activate returns 400 when version query parameter is missing")
    void testActivateVersionMissingParam() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);

        Response response = resource.activateVersion("fraud_model_v1", "  ", sc);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("DELETE /models/{name}/versions/{version} deletes version and returns 200 OK")
    void testDeleteVersionSuccess() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);

        when(registryService.deleteModelVersion("fraud_model_v1", "1.0.0")).thenReturn(true);

        Response response = resource.deleteVersion("fraud_model_v1", "1.0.0", sc);
        assertThat(response.getStatus()).isEqualTo(200);

        ModelActionResponse body = (ModelActionResponse) response.getEntity();
        assertThat(body.status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("DELETE /models/{name}/versions/{version} returns 404 when version not found")
    void testDeleteVersionNotFound() {
        SecurityContext sc = createSecurityContext("admin_user", EngineRole.ADMIN);

        when(registryService.deleteModelVersion("fraud_model_v1", "9.9.9")).thenReturn(false);

        Response response = resource.deleteVersion("fraud_model_v1", "9.9.9", sc);
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("ModelResource RBAC: Secured and RolesAllowed annotations on endpoints")
    void testRbacAnnotations() throws NoSuchMethodException {
        assertThat(ModelResource.class.isAnnotationPresent(Secured.class)).isTrue();

        // POST /models -> RolesAllowed(ADMIN, DATA_SCIENTIST)
        Method postMethod = ModelResource.class.getMethod("uploadMultipart", List.class, SecurityContext.class);
        RolesAllowed postRoles = postMethod.getAnnotation(RolesAllowed.class);
        assertThat(postRoles).isNotNull();
        assertThat(Arrays.asList(postRoles.value())).containsExactlyInAnyOrder("ADMIN", "DATA_SCIENTIST");

        // PUT /models/{name}/activate -> RolesAllowed(ADMIN)
        Method putMethod = ModelResource.class.getMethod("activateVersion", String.class, String.class, SecurityContext.class);
        RolesAllowed putRoles = putMethod.getAnnotation(RolesAllowed.class);
        assertThat(putRoles).isNotNull();
        assertThat(Arrays.asList(putRoles.value())).containsExactlyInAnyOrder("ADMIN");

        // DELETE /models/{name}/versions/{version} -> RolesAllowed(ADMIN)
        Method deleteMethod = ModelResource.class.getMethod("deleteVersion", String.class, String.class, SecurityContext.class);
        RolesAllowed deleteRoles = deleteMethod.getAnnotation(RolesAllowed.class);
        assertThat(deleteRoles).isNotNull();
        assertThat(Arrays.asList(deleteRoles.value())).containsExactlyInAnyOrder("ADMIN");
    }

    @Test
    @DisplayName("Verify JWT SecurityContext evaluates roles properly for DATA_SCIENTIST and ADMIN")
    void testSecurityContextRoles() {
        SecurityContext admin = createSecurityContext("admin", EngineRole.ADMIN);
        assertThat(admin.isUserInRole("ADMIN")).isTrue();
        assertThat(admin.isUserInRole("DATA_SCIENTIST")).isFalse();

        SecurityContext scientist = createSecurityContext("scientist", EngineRole.DATA_SCIENTIST);
        assertThat(scientist.isUserInRole("DATA_SCIENTIST")).isTrue();
        assertThat(scientist.isUserInRole("ADMIN")).isFalse();
        assertThat(scientist.isUserInRole("ENGINEER")).isFalse();

        SecurityContext engineer = createSecurityContext("engineer", EngineRole.ENGINEER);
        assertThat(engineer.isUserInRole("ENGINEER")).isTrue();
        assertThat(engineer.isUserInRole("DATA_SCIENTIST")).isFalse();
        assertThat(engineer.isUserInRole("ADMIN")).isFalse();
    }
}
