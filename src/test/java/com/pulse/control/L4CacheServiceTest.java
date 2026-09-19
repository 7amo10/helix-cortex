package com.pulse.control;

import com.helix.core.cache.l4.L4RedisRuleCache;
import com.pulse.boundary.dto.CacheMetricsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class L4CacheServiceTest {

    private L4CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new L4CacheService("localhost", 6379, 2000, true);
    }

    @AfterEach
    void tearDown() {
        if (cacheService != null) {
            cacheService.destroy();
        }
    }

    @Test
    @DisplayName("Should initialize L4 cache service with fallback capability")
    void testInitialization() {
        assertThat(cacheService.getCache()).isNotNull();
        assertThat(cacheService.getHost()).isEqualTo("localhost");
        assertThat(cacheService.getPort()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Should accurately record metrics on cache hit and miss")
    void testMetricsCalculation() {
        L4RedisRuleCache cache = cacheService.getCache();
        String testHash = "metric-test-hash-" + System.nanoTime();
        byte[] payload = "BYTECODE_DATA".getBytes(StandardCharsets.UTF_8);

        // Initial miss
        cache.getBytecode(testHash);
        assertThat(cacheService.getMissCount()).isGreaterThanOrEqualTo(1L);

        // Populate and hit
        cache.putBytecode(testHash, "MetricRule", "1.0.0", payload);
        var hit = cache.getBytecode(testHash);
        assertThat(hit).isPresent();
        assertThat(cacheService.getHitCount()).isGreaterThanOrEqualTo(1L);

        CacheMetricsResponse metrics = cacheService.getMetrics();
        assertThat(metrics.hitCount()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.missCount()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.hitRatio()).isGreaterThan(0.0);
        assertThat(metrics.host()).isEqualTo("localhost");
        assertThat(metrics.port()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Should trigger registered invalidation listeners on invalidate")
    void testInvalidationListeners() {
        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        AtomicReference<String> invalidatedRule = new AtomicReference<>();

        cacheService.registerInvalidationListener(ruleName -> {
            listenerInvoked.set(true);
            invalidatedRule.set(ruleName);
        });

        cacheService.invalidate("FraudDetectionRule");

        assertThat(listenerInvoked.get()).isTrue();
        assertThat(invalidatedRule.get()).isEqualTo("FraudDetectionRule");
    }

    @Test
    @DisplayName("Should clear all cache entries and broadcast cluster invalidation")
    void testClearAll() {
        AtomicReference<String> clearEvent = new AtomicReference<>();
        cacheService.registerInvalidationListener(clearEvent::set);

        cacheService.clear();

        assertThat(clearEvent.get()).isEqualTo("__ALL__");
    }
}
