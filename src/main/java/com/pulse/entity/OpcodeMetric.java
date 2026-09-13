package com.pulse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity capturing granular opcode execution telemetry and antipattern diagnostics.
 */
@Entity
@Table(name = "opcode_metric")
public class OpcodeMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private RuleSession session;

    @Column(name = "total_opcode_count", nullable = false)
    private long totalOpcodeCount;

    @Column(name = "execution_time_nanos", nullable = false)
    private long executionTimeNanos;

    @Column(name = "antipattern_flag", nullable = false)
    private boolean antipatternFlag;

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    public OpcodeMetric() {
        this.evaluatedAt = Instant.now();
    }

    public OpcodeMetric(long totalOpcodeCount, long executionTimeNanos, boolean antipatternFlag) {
        this();
        this.totalOpcodeCount = totalOpcodeCount;
        this.executionTimeNanos = executionTimeNanos;
        this.antipatternFlag = antipatternFlag;
    }

    public OpcodeMetric(RuleSession session, long totalOpcodeCount, long executionTimeNanos, boolean antipatternFlag) {
        this(totalOpcodeCount, executionTimeNanos, antipatternFlag);
        this.session = session;
    }

    public Long getId() {
        return id;
    }

    public RuleSession getSession() {
        return session;
    }

    public void setSession(RuleSession session) {
        this.session = session;
    }

    public long getTotalOpcodeCount() {
        return totalOpcodeCount;
    }

    public void setTotalOpcodeCount(long totalOpcodeCount) {
        this.totalOpcodeCount = totalOpcodeCount;
    }

    public long getExecutionTimeNanos() {
        return executionTimeNanos;
    }

    public void setExecutionTimeNanos(long executionTimeNanos) {
        this.executionTimeNanos = executionTimeNanos;
    }

    public boolean isAntipatternFlag() {
        return antipatternFlag;
    }

    public void setAntipatternFlag(boolean antipatternFlag) {
        this.antipatternFlag = antipatternFlag;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OpcodeMetric that = (OpcodeMetric) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
