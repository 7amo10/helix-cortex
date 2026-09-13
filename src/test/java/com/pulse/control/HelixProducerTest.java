package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.api.RuleEngine;
import com.helix.api.profiler.Profiler;
import com.helix.core.parser.RuleSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HelixProducerTest {

    private HelixProducer producer;

    @BeforeEach
    void setUp() {
        producer = new HelixProducer();
    }

    @Test
    @DisplayName("produceRuleEngine returns a non-null RuleEngine")
    void testProduceRuleEngine() {
        RuleEngine engine = producer.produceRuleEngine();
        assertThat(engine).isNotNull();
    }

    @Test
    @DisplayName("RuleEngine compiled rule does not throw on valid rule")
    void testCompileRule() throws Exception {
        RuleEngine engine = producer.produceRuleEngine();
        RuleSchema rule = new RuleSchema(
                "test-rule",
                "1.0",
                "Test rule expression",
                "GENERAL",
                "x > 10",
                Map.of("x", Integer.class)
        );

        CompiledRule compiled = engine.compile(rule);
        assertThat(compiled).isNotNull();
        assertThat(compiled.getName()).isEqualTo("test-rule");
    }

    @Test
    @DisplayName("produceProfiler returns non-null Profiler with isRunning == false initially")
    void testProduceProfiler() {
        RuleEngine engine = producer.produceRuleEngine();
        Profiler profiler = producer.produceProfiler(engine);

        assertThat(profiler).isNotNull();
        assertThat(profiler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("disposeProfiler stops a running profiler")
    void testDisposeProfiler() {
        RuleEngine engine = producer.produceRuleEngine();
        Profiler profiler = producer.produceProfiler(engine);

        profiler.start();
        assertThat(profiler.isRunning()).isTrue();

        producer.disposeProfiler(profiler);
        assertThat(profiler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("disposeRuleEngine handles closeable engine without error")
    void testDisposeRuleEngine() {
        RuleEngine engine = producer.produceRuleEngine();
        assertThatCode(() -> producer.disposeRuleEngine(engine)).doesNotThrowAnyException();
    }
}
