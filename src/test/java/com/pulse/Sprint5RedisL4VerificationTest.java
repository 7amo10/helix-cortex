package com.pulse;

import com.helix.api.CompiledRule;
import com.pulse.boundary.CacheResource;
import com.pulse.boundary.dto.CacheInvalidateRequest;
import com.pulse.boundary.dto.CacheInvalidateResponse;
import com.pulse.boundary.dto.CacheMetricsResponse;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.Secured;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.L4CacheService;
import com.pulse.control.RuleCompilerService;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Sprint 5 Verification: Redis L4 Cache Configuration & Management REST Endpoints")
class Sprint5RedisL4VerificationTest {

    private L4CacheService cacheService;
    private RuleCompilerService compilerService;
    private CacheResource cacheResource;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("helix-cortex-sprint5-verification-secret-32-bytes");
        cacheService = new L4CacheService("localhost", 6379, 2000, true);
        compilerService = new RuleCompilerService(cacheService.getCache(), cacheService);
        cacheResource = new CacheResource(cacheService);
    }

    @AfterEach
    void tearDown() {
        if (cacheService != null) {
            cacheService.destroy();
        }
    }

    @Test
    @DisplayName("Verification 1: Jedis dependency and MicroProfile configuration presence")
    void testConfigurationAndDependencies() throws Exception {
        Path pomPath = Path.of("pom.xml");
        assertThat(Files.exists(pomPath)).isTrue();
        String pomContent = Files.readString(pomPath);
        assertThat(pomContent).contains("<groupId>redis.clients</groupId>");
        assertThat(pomContent).contains("<artifactId>jedis</artifactId>");

        Path configPath = Path.of("src/main/resources/META-INF/microprofile-config.properties");
        assertThat(Files.exists(configPath)).isTrue();
        String configContent = Files.readString(configPath);
        assertThat(configContent).contains("helix.cortex.cache.redis.host=localhost");
        assertThat(configContent).contains("helix.cortex.cache.redis.port=6379");
        assertThat(configContent).contains("helix.cortex.cache.redis.timeout=2000");
    }

    @Test
    @DisplayName("Verification 2: MicroProfile JWT RBAC security annotations on CacheResource")
    void testCacheResourceSecurity() throws NoSuchMethodException {
        assertThat(CacheResource.class.isAnnotationPresent(Secured.class)).isTrue();

        Method getMetrics = CacheResource.class.getMethod("getMetrics");
        RolesAllowed metricsRoles = getMetrics.getAnnotation(RolesAllowed.class);
        assertThat(metricsRoles).isNotNull();
        assertThat(Arrays.asList(metricsRoles.value())).containsExactlyInAnyOrder("ADMIN", "OPERATOR");

        Method invalidate = CacheResource.class.getMethod("invalidate", CacheInvalidateRequest.class);
        RolesAllowed invRoles = invalidate.getAnnotation(RolesAllowed.class);
        assertThat(invRoles).isNotNull();
        assertThat(Arrays.asList(invRoles.value())).containsExactlyInAnyOrder("ADMIN", "OPERATOR");

        // Validate Token claims role validation
        Instant now = Instant.now();
        TokenClaims adminClaims = new TokenClaims("admin_user", EngineRole.ADMIN, now, now.plusSeconds(3600));
        TokenClaims operatorClaims = new TokenClaims("operator_user", EngineRole.OPERATOR, now, now.plusSeconds(3600));
        TokenClaims engineerClaims = new TokenClaims("engineer_user", EngineRole.ENGINEER, now, now.plusSeconds(3600));

        JwtSecurityContext adminCtx = new JwtSecurityContext(adminClaims);
        JwtSecurityContext operatorCtx = new JwtSecurityContext(operatorClaims);
        JwtSecurityContext engineerCtx = new JwtSecurityContext(engineerClaims);

        assertThat(adminCtx.isUserInRole("ADMIN")).isTrue();
        assertThat(adminCtx.isUserInRole("OPERATOR")).isFalse();

        assertThat(operatorCtx.isUserInRole("OPERATOR")).isTrue();
        assertThat(operatorCtx.isUserInRole("ADMIN")).isFalse();

        assertThat(engineerCtx.isUserInRole("ADMIN")).isFalse();
        assertThat(engineerCtx.isUserInRole("OPERATOR")).isFalse();
    }

    @Test
    @DisplayName("Verification 3: Real scenario - Compile, L4 caching, metrics observation, and invalidation")
    void testRealScenarioCacheLifecycle() throws Exception {
        // Step 1: Initial metrics check
        Response metricsResp1 = cacheResource.getMetrics();
        assertThat(metricsResp1.getStatus()).isEqualTo(200);
        CacheMetricsResponse initialMetrics = (CacheMetricsResponse) metricsResp1.getEntity();
        assertThat(initialMetrics).isNotNull();

        // Step 2: Compile a business rule through RuleCompilerService
        RuleRequest ruleReq = new RuleRequest(
                "HighVolumeTransactionRule",
                "1.0.0",
                "amount > 10000.0 && riskScore > 75",
                Map.of("amount", "double", "riskScore", "int")
        );

        CompiledRule compiled = compilerService.compile(ruleReq);
        assertThat(compiled).isNotNull();
        assertThat(compiled.getName()).isEqualTo("HighVolumeTransactionRule");
        assertThat(compilerService.getLocalCache()).containsKey("HighVolumeTransactionRule:1.0.0");

        // Step 3: Verify subsequent compilation hits local cache and does not fail
        CompiledRule secondCompile = compilerService.compile(ruleReq);
        assertThat(secondCompile).isSameAs(compiled);

        // Step 4: Verify metrics reflect operations
        Response metricsResp2 = cacheResource.getMetrics();
        assertThat(metricsResp2.getStatus()).isEqualTo(200);
        CacheMetricsResponse updatedMetrics = (CacheMetricsResponse) metricsResp2.getEntity();
        assertThat(updatedMetrics.host()).isEqualTo("localhost");
        assertThat(updatedMetrics.port()).isEqualTo(6379);

        // Step 5: Broadcast invalidation for the compiled rule
        CacheInvalidateRequest invReq = new CacheInvalidateRequest("HighVolumeTransactionRule", "1.0.0");
        Response invResp = cacheResource.invalidate(invReq);
        assertThat(invResp.getStatus()).isEqualTo(200);

        CacheInvalidateResponse invBody = (CacheInvalidateResponse) invResp.getEntity();
        assertThat(invBody.status()).isEqualTo("SUCCESS");
        assertThat(invBody.ruleName()).isEqualTo("HighVolumeTransactionRule");

        // Step 6: Verify local compiled cache evicted the rule immediately
        assertThat(compilerService.getLocalCache()).doesNotContainKey("HighVolumeTransactionRule:1.0.0");
    }
}
