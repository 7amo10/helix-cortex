package com.pulse.entity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity tracking the submitted rule definition, compilation identifier, and execution status.
 * Configured with L2 caching for fast read access on engineer and admin dashboards.
 */
@Entity
@Table(name = "rule_session", indexes = {
        @Index(name = "idx_rule_session_engineer", columnList = "engineer_id")
})
@Cacheable(true)
public class RuleSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "engineer_id", length = 100, nullable = false)
    private String engineerId;

    @Column(name = "rule_json", columnDefinition = "TEXT", nullable = false)
    private String ruleJson;

    @Column(name = "compiled_rule_id", length = 255)
    private String compiledRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private SessionStatus status;

    @Version
    private Long version;

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 10)
    private List<OpcodeMetric> metrics = new ArrayList<>();

    public RuleSession() {
        this.createdAt = Instant.now();
        this.status = SessionStatus.COMPILED;
    }

    public RuleSession(String engineerId, String ruleJson, String compiledRuleId, SessionStatus status) {
        this();
        this.engineerId = engineerId;
        this.ruleJson = ruleJson;
        this.compiledRuleId = compiledRuleId;
        this.status = status != null ? status : SessionStatus.COMPILED;
    }

    public void addMetric(OpcodeMetric metric) {
        if (metric != null) {
            metrics.add(metric);
            metric.setSession(this);
        }
    }

    public void removeMetric(OpcodeMetric metric) {
        if (metric != null) {
            metrics.remove(metric);
            metric.setSession(null);
        }
    }

    public Long getId() {
        return id;
    }

    public String getEngineerId() {
        return engineerId;
    }

    public void setEngineerId(String engineerId) {
        this.engineerId = engineerId;
    }

    public String getRuleJson() {
        return ruleJson;
    }

    public void setRuleJson(String ruleJson) {
        this.ruleJson = ruleJson;
    }

    public String getCompiledRuleId() {
        return compiledRuleId;
    }

    public void setCompiledRuleId(String compiledRuleId) {
        this.compiledRuleId = compiledRuleId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OpcodeMetric> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleSession that = (RuleSession) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
