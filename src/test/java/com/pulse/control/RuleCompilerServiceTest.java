package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.pulse.boundary.dto.RuleRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
