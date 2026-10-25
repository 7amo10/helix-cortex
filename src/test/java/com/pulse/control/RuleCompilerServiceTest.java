package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.sandbox.CapabilityViolationException;
import com.helix.api.sandbox.TenantTrustLevel;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.pulse.boundary.dto.RuleRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleCompilerServiceTest {

    private L4CacheService cacheService;
    private RuleCompilerService compilerService;

    @BeforeEach
    void setUp() {
        cacheService = new L4CacheService("localhost", 6379, 2000, true);
        L4RedisRuleCache l4Cache = cacheService.getCache();
        compilerService = new RuleCompilerService(l4Cache, cacheService);
    }

    @AfterEach
    void tearDown() {
        if (cacheService != null) {
            cacheService.destroy();
        }
    }

    @Test
    @DisplayName("Should compile rule and cache bytecode in L4 and local tiers")
    void testCompileRuleAndCacheTiers() throws Exception {
        RuleRequest request = new RuleRequest(
                "DiscountRule",
                "1.0.0",
                "price > 100 && member == true",
                Map.of("price", "double", "member", "boolean")
        );

        CompiledRule compiledRule = compilerService.compile(request);

        assertThat(compiledRule).isNotNull();
        assertThat(compiledRule.getName()).isEqualTo("DiscountRule");
        assertThat(compilerService.getLocalCache()).containsKey("DiscountRule:1.0.0");

        // Second compile should hit local cache without re-compilation
        CompiledRule cachedRule = compilerService.compile(request);
        assertThat(cachedRule).isSameAs(compiledRule);
    }

    @Test
    @DisplayName("Should evict local cached rules when invalidation is triggered")
    void testEvictionOnInvalidation() throws Exception {
        RuleRequest request = new RuleRequest(
                "EvictionRule",
                "1.0.0",
                "score >= 50",
                Map.of("score", "int")
        );

        compilerService.compile(request);
        assertThat(compilerService.getLocalCache()).containsKey("EvictionRule:1.0.0");

        cacheService.invalidate("EvictionRule");

        assertThat(compilerService.getLocalCache()).doesNotContainKey("EvictionRule:1.0.0");
    }

    @Test
    @DisplayName("Should extract model references from unquoted, quoted, and compound AST expressions")
    void testExtractModelReferences() throws Exception {
        var models1 = compilerService.extractModelReferences("amount > 1000 && ML(fraud_model_v1) > 0.85");
        assertThat(models1).containsExactly("fraud_model_v1");

        var models2 = compilerService.extractModelReferences("ML('fraud_model_v1') > 0.85 && ML('risk_model_v2') <= 0.20");
        assertThat(models2).containsExactly("fraud_model_v1", "risk_model_v2");

        var models3 = compilerService.extractModelReferences("is_vpn == 1.0 || (amount <= 500 && member == true)");
        assertThat(models3).isEmpty();
    }

    @Test
    @DisplayName("Should compile rule with active ML model and register descriptor in LocalModelRegistry")
    void testCompileWithActiveModel() throws Exception {
        var descriptor = com.helix.api.ml.OnnxModelDescriptor.builder()
                .modelName("fraud_model_v1")
                .version("1.0.0")
                .modelPath("/tmp/models/fraud_model_v1.onnx")
                .inputFeatures(java.util.List.of("amount", "is_vpn_or_proxy"))
                .outputTensorName("probabilities")
                .outputIndex(1)
                .active(true)
                .build();

        compilerService.getLocalModelRegistry().registerModel(descriptor);

        RuleRequest request = new RuleRequest(
                "MlFraudRule",
                "1.0.0",
                "amount > 500 && ML(fraud_model_v1) > 0.80",
                Map.of("amount", "double")
        );

        CompiledRule compiledRule = compilerService.compile(request);
        assertThat(compiledRule).isNotNull();
        assertThat(compiledRule.getName()).isEqualTo("MlFraudRule");

        // Verify descriptor is accessible in localModelRegistry
        var resolved = compilerService.getLocalModelRegistry().resolveModel("fraud_model_v1");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().version()).isEqualTo("1.0.0");
        assertThat(resolved.get().active()).isTrue();
    }

    @Test
    @DisplayName("Should fail compilation with RuleCompilationException when referenced ML model is not registered")
    void testCompileWithUnregisteredModelFails() {
        RuleRequest request = new RuleRequest(
                "UnregisteredMlRule",
                "1.0.0",
                "amount > 1000 && ML(non_existent_model) > 0.90",
                Map.of("amount", "double")
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> compilerService.compile(request))
                .isInstanceOf(com.helix.api.RuleCompilationException.class)
                .hasMessageContaining("Referenced ML model 'non_existent_model' does not exist or has no active version");
    }

    @Test
    @DisplayName("Should fail compilation when referenced ML model exists but has active == false")
    void testCompileWithInactiveModelFails() {
        ModelRegistryService mockRegistry = org.mockito.Mockito.mock(ModelRegistryService.class);
        var inactiveDesc = com.helix.api.ml.OnnxModelDescriptor.builder()
                .modelName("legacy_model")
                .version("0.9.0")
                .modelPath("/tmp/models/legacy_model.onnx")
                .inputFeatures(java.util.List.of("f1", "f2"))
                .outputTensorName("probabilities")
                .outputIndex(1)
                .active(false)
                .build();

        org.mockito.Mockito.when(mockRegistry.resolveModel("legacy_model")).thenReturn(java.util.Optional.of(inactiveDesc));
        compilerService.setModelRegistryService(mockRegistry);

        RuleRequest request = new RuleRequest(
                "InactiveModelRule",
                "1.0.0",
                "ML(legacy_model) > 0.50",
                Map.of()
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> compilerService.compile(request))
                .isInstanceOf(com.helix.api.RuleCompilationException.class)
                .hasMessageContaining("Referenced ML model 'legacy_model' does not exist or has no active version");
    }

    @Nested
    @DisplayName("Sandbox & TenantTrustLevel AST Capability Enforcement Tests")
    class SandboxCapabilityTests {

        @Test
        @DisplayName("Untrusted rule with forbidden method call throws CapabilityViolationException with diagnostic details")
        void testUntrustedRuleWithForbiddenMethodThrowsException() {
            RuleRequest request = new RuleRequest(
                    "UntrustedMaliciousRule",
                    "1.0.0",
                    "System.exit(1)",
                    Map.of()
            );

            assertThatThrownBy(() -> compilerService.compile(request, TenantTrustLevel.UNTRUSTED))
                    .isInstanceOf(CapabilityViolationException.class)
                    .satisfies(ex -> {
                        CapabilityViolationException cve = (CapabilityViolationException) ex;
                        assertThat(cve.getViolatingClass()).contains("java.lang.System");
                        assertThat(cve.getViolatingMethod()).isEqualTo("exit");
                    });
        }

        @Test
        @DisplayName("Untrusted rule with forbidden ProcessBuilder throws CapabilityViolationException")
        void testUntrustedRuleWithProcessBuilderThrowsException() {
            RuleRequest request = new RuleRequest(
                    "UntrustedExecRule",
                    "1.0.0",
                    "ProcessBuilder.start()",
                    Map.of()
            );

            assertThatThrownBy(() -> compilerService.compile(request, TenantTrustLevel.UNTRUSTED))
                    .isInstanceOf(CapabilityViolationException.class)
                    .satisfies(ex -> {
                        CapabilityViolationException cve = (CapabilityViolationException) ex;
                        assertThat(cve.getViolatingClass()).contains("java.lang.ProcessBuilder");
                    });
        }

        @Test
        @DisplayName("Untrusted safe rule compiles cleanly into an executable CompiledRule")
        void testUntrustedSafeRuleCompilesSuccessfully() throws Exception {
            RuleRequest request = new RuleRequest(
                    "UntrustedSafeRule",
                    "1.0.0",
                    "balance > 1000 && active == true",
                    Map.of("balance", "double", "active", "boolean")
            );

            CompiledRule compiledRule = compilerService.compile(request, TenantTrustLevel.UNTRUSTED);

            assertThat(compiledRule).isNotNull();
            assertThat(compiledRule.getName()).isEqualTo("UntrustedSafeRule");
            assertThat(compilerService.getLocalCache()).containsKey("UntrustedSafeRule:1.0.0:sandboxed");
        }

        @Test
        @DisplayName("Trusted rule bypasses capability enforcement and compiles without invoking enforcer")
        void testTrustedRuleBypassesCapabilityEnforcer() throws Exception {
            RuleRequest request = new RuleRequest(
                    "TrustedInternalRule",
                    "1.0.0",
                    "price * 0.90 < limit",
                    Map.of("price", "double", "limit", "double")
            );

            CompiledRule compiled = compilerService.compile(request, TenantTrustLevel.TRUSTED);

            assertThat(compiled).isNotNull();
            assertThat(compiled.getName()).isEqualTo("TrustedInternalRule");
            assertThat(compilerService.getLocalCache()).containsKey("TrustedInternalRule:1.0.0");
        }

        @Test
        @DisplayName("Single-argument compile preserves full backward compatibility by defaulting to TRUSTED")
        void testDefaultCompileIsTrusted() throws Exception {
            RuleRequest request = new RuleRequest(
                    "LegacyCompatRule",
                    "1.0.0",
                    "count > 5",
                    Map.of("count", "int")
            );

            CompiledRule compiled = compilerService.compile(request);

            assertThat(compiled).isNotNull();
            assertThat(compiled.getName()).isEqualTo("LegacyCompatRule");
            assertThat(compilerService.getLocalCache()).containsKey("LegacyCompatRule:1.0.0");
        }
    }
}
