package com.pulse.control;

import com.pulse.app.CortexApplication;
import com.pulse.boundary.AuthResource;
import com.pulse.boundary.JarAnalysisResource;
import com.pulse.boundary.RuleResource;
import com.pulse.boundary.TelemetryResource;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Task 4.6: MicroProfile OpenAPI Annotations Acceptance Criteria Verification")
class OpenApiConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().resolve("helix-cortex");

    private Path resolveFile(String relativePath) {
        Path p = Path.of(relativePath);
        if (Files.exists(p)) {
            return p;
        }
        return PROJECT_ROOT.resolve(relativePath);
    }

    @Test
    @DisplayName("Acceptance Criteria 1: microprofile-openapi-api dependency present with provided scope in pom.xml")
    void testPomOpenApiDependency() throws Exception {
        Path pomPath = resolveFile("pom.xml");
        assertThat(Files.exists(pomPath)).isTrue();
        String pom = Files.readString(pomPath);

        assertThat(pom).contains("microprofile-openapi-api");
        assertThat(pom).contains("<scope>provided</scope>");
    }

    @Test
    @DisplayName("Acceptance Criteria 2: @OpenAPIDefinition on CortexApplication with title, version, and description")
    void testOpenApiDefinitionOnCortexApplication() {
        OpenAPIDefinition def = CortexApplication.class.getAnnotation(OpenAPIDefinition.class);
        assertThat(def).isNotNull();
        assertThat(def.info().title()).isEqualTo("helix-cortex API");
        assertThat(def.info().version()).isEqualTo("1.0.0");
        assertThat(def.info().description()).contains("helix-jvm-engine");
    }

    @Test
    @DisplayName("Acceptance Criteria 3: Resource tags: auth, rules, jars, telemetry")
    void testResourceTags() {
        Tag authTag = AuthResource.class.getAnnotation(Tag.class);
        assertThat(authTag).isNotNull();
        assertThat(authTag.name()).isEqualTo("auth");

        Tag ruleTag = RuleResource.class.getAnnotation(Tag.class);
        assertThat(ruleTag).isNotNull();
        assertThat(ruleTag.name()).isEqualTo("rules");

        Tag jarTag = JarAnalysisResource.class.getAnnotation(Tag.class);
        assertThat(jarTag).isNotNull();
        assertThat(jarTag.name()).isEqualTo("jars");

        Tag telTag = TelemetryResource.class.getAnnotation(Tag.class);
        assertThat(telTag).isNotNull();
        assertThat(telTag.name()).isEqualTo("telemetry");
    }

    @Test
    @DisplayName("Acceptance Criteria 4: @Operation and @APIResponse on endpoint methods")
    void testOperationAndResponseAnnotations() {
        Class<?>[] resourceClasses = {
                AuthResource.class,
                RuleResource.class,
                JarAnalysisResource.class,
                TelemetryResource.class
        };

        for (Class<?> clazz : resourceClasses) {
            long annotatedCount = Arrays.stream(clazz.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Operation.class))
                    .count();
            assertThat(annotatedCount).as("Resource " + clazz.getSimpleName() + " must have @Operation on endpoints")
                    .isGreaterThan(0);

            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Operation.class)) {
                    boolean hasResponse = m.isAnnotationPresent(APIResponse.class) || m.isAnnotationPresent(APIResponses.class);
                    assertThat(hasResponse).as("Method " + m.getName() + " in " + clazz.getSimpleName() + " must have @APIResponse")
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Acceptance Criteria 5: @SecurityScheme declared for Bearer JWT")
    void testSecuritySchemeDeclared() {
        OpenAPIDefinition def = CortexApplication.class.getAnnotation(OpenAPIDefinition.class);
        assertThat(def).isNotNull();

        SecurityScheme[] schemes = def.components().securitySchemes();
        assertThat(schemes).isNotEmpty();
        assertThat(schemes[0].securitySchemeName()).isEqualTo("BearerAuth");
        assertThat(schemes[0].bearerFormat()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("Acceptance Criteria 6: WildFly standalone.xml includes microprofile openapi subsystem")
    void testStandaloneXmlOpenApiSubsystem() throws Exception {
        Path standalonePath = resolveFile("docker/standalone.xml");
        assertThat(Files.exists(standalonePath)).isTrue();
        String content = Files.readString(standalonePath);

        assertThat(content).contains("org.wildfly.extension.microprofile.openapi-smallrye");
        assertThat(content).contains("microprofile-openapi-smallrye");
    }
}
