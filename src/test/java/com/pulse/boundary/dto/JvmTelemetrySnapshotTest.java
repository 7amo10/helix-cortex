package com.pulse.boundary.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JvmTelemetrySnapshotTest {

    @Test
    @DisplayName("JvmTelemetrySnapshot record stores JVM metric fields accurately")
    void testSnapshotFields() {
        Instant now = Instant.now();
        JvmTelemetrySnapshot snapshot = new JvmTelemetrySnapshot(
                104857600L,
                1073741824L,
                9.765625,
                12L,
                450L,
                24,
                8,
                3600000L,
                now
        );

        assertThat(snapshot.heapUsedBytes()).isEqualTo(104857600L);
        assertThat(snapshot.heapMaxBytes()).isEqualTo(1073741824L);
        assertThat(snapshot.heapUsedPercent()).isCloseTo(9.765625, within(0.001));
        assertThat(snapshot.gcCollectionCount()).isEqualTo(12L);
        assertThat(snapshot.gcCollectionTimeMs()).isEqualTo(450L);
        assertThat(snapshot.threadCount()).isEqualTo(24);
        assertThat(snapshot.daemonThreadCount()).isEqualTo(8);
        assertThat(snapshot.uptimeMs()).isEqualTo(3600000L);
        assertThat(snapshot.sampledAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("JvmTelemetrySnapshot initializes sampledAt when null")
    void testDefaultSampledAt() {
        JvmTelemetrySnapshot snapshot = new JvmTelemetrySnapshot(
                50000L, 100000L, 50.0, 1L, 10L, 5, 2, 1000L, null
        );
        assertThat(snapshot.sampledAt()).isNotNull();
        assertThat(snapshot.sampledAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Record equality and hashcode contract")
    void testEquality() {
        Instant now = Instant.now();
        JvmTelemetrySnapshot s1 = new JvmTelemetrySnapshot(10L, 20L, 50.0, 1L, 5L, 2, 1, 100L, now);
        JvmTelemetrySnapshot s2 = new JvmTelemetrySnapshot(10L, 20L, 50.0, 1L, 5L, 2, 1, 100L, now);
        JvmTelemetrySnapshot s3 = new JvmTelemetrySnapshot(15L, 20L, 75.0, 1L, 5L, 2, 1, 100L, now);

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
        assertThat(s1).isNotEqualTo(s3);
    }
}
