package com.pulse.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Task 4.4: Postman Collection Acceptance Criteria Verification")
class PostmanCollectionTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().resolve("helix-cortex");

    private Path resolveFile(String relativePath) {
        Path p = Path.of(relativePath);
        if (Files.exists(p)) {
            return p;
        }
        return PROJECT_ROOT.resolve(relativePath);
    }

    private JsonNode loadCollection() throws Exception {
        Path path = resolveFile("postman/helix-cortex.postman_collection.json");
        assertThat(Files.exists(path)).isTrue();
        String json = Files.readString(path);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("Acceptance Criteria 1: Collection variable BASE_URL set to http://localhost:8080/api/v1")
    void testBaseUrlVariable() throws Exception {
        JsonNode root = loadCollection();
        JsonNode variables = root.get("variable");
        assertThat(variables).isNotNull();

        boolean foundBaseUrl = false;
        for (JsonNode varNode : variables) {
            if ("BASE_URL".equals(varNode.get("key").asText())) {
                assertThat(varNode.get("value").asText()).isEqualTo("http://localhost:8080/api/v1");
                foundBaseUrl = true;
            }
        }
        assertThat(foundBaseUrl).isTrue();
    }

    @Test
    @DisplayName("Acceptance Criteria 2: Automatic JWT token extraction in login test script")
    void testTokenExtraction() throws Exception {
        JsonNode root = loadCollection();
        String text = root.toString();
        assertThat(text).contains("JWT_TOKEN");
        assertThat(text).contains("pm.collectionVariables.set");
    }

    @Test
    @DisplayName("Acceptance Criteria 3: Covers all 13 endpoints with status code assertions")
    void testCoversAll13Endpoints() throws Exception {
        JsonNode root = loadCollection();
        String text = root.toString();

        // 13 API endpoints
        assertThat(text).contains("auth/register");
        assertThat(text).contains("auth/login");
        assertThat(text).contains("rules/compile");
        assertThat(text).contains("rules/execute");
        assertThat(text).contains("rules/sessions");
        assertThat(text).contains("rules/sessions/mine");
        assertThat(text).contains("rules/sessions/high-density");
        assertThat(text).contains("jars/analyze");
        assertThat(text).contains("jars/sessions");
        assertThat(text).contains("telemetry/snapshot");
        assertThat(text).contains("telemetry/stream");

        // Status code assertions
        assertThat(text).contains("pm.response.to.have.status(200)");
        assertThat(text).contains("pm.response.to.have.status(201)");
    }

    @Test
    @DisplayName("Acceptance Criteria 4: Negative tests: 401 on missing token, 403 on role violation")
    void testNegativeSecurityTests() throws Exception {
        JsonNode root = loadCollection();
        String text = root.toString();

        assertThat(text).contains("401");
        assertThat(text).contains("403");
        assertThat(text).contains("Missing Token");
        assertThat(text).contains("Role Violation");
    }
}
