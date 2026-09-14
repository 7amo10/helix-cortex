package com.pulse.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RuleAntipatternEventTest {

    @Test
    @DisplayName("AntipatternType defines all required antipattern constants")
    void testAntipatternConstants() {
        assertThat(AntipatternType.STRING_CONCAT_LOOP).isEqualTo("STRING_CONCAT_LOOP");
        assertThat(AntipatternType.EXCESSIVE_OBJECT_CREATION).isEqualTo("EXCESSIVE_OBJECT_CREATION");
        assertThat(AntipatternType.REDUNDANT_INSTANCEOF).isEqualTo("REDUNDANT_INSTANCEOF");
    }

    @Test
    @DisplayName("RuleAntipatternEvent is a Java record with required fields")
    void testRuleAntipatternEventRecord() {
        assertThat(RuleAntipatternEvent.class.isRecord()).isTrue();

        Instant now = Instant.now();
        RuleAntipatternEvent event = new RuleAntipatternEvent(
                "com.example.HeavyLoopService",
                AntipatternType.STRING_CONCAT_LOOP,
                now,
                42L
        );

        assertThat(event.className()).isEqualTo("com.example.HeavyLoopService");
        assertThat(event.antipatternType()).isEqualTo("STRING_CONCAT_LOOP");
        assertThat(event.detectedAt()).isEqualTo(now);
        assertThat(event.jarAnalysisId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("RuleAntipatternEvent defaults detectedAt if null is provided")
    void testDefaultDetectedAt() {
        RuleAntipatternEvent event = new RuleAntipatternEvent(
                "com.example.AllocService",
                AntipatternType.EXCESSIVE_OBJECT_CREATION,
                null,
                100L
        );

        assertThat(event.detectedAt()).isNotNull();
        assertThat(event.detectedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("RuleAntipatternEvent record equals and hashCode work as expected")
    void testRecordEquality() {
        Instant now = Instant.now();
        RuleAntipatternEvent event1 = new RuleAntipatternEvent("com.test.Cls", AntipatternType.REDUNDANT_INSTANCEOF, now, 1L);
        RuleAntipatternEvent event2 = new RuleAntipatternEvent("com.test.Cls", AntipatternType.REDUNDANT_INSTANCEOF, now, 1L);
        RuleAntipatternEvent event3 = new RuleAntipatternEvent("com.test.Cls", AntipatternType.STRING_CONCAT_LOOP, now, 1L);

        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
        assertThat(event1).isNotEqualTo(event3);
    }
}
