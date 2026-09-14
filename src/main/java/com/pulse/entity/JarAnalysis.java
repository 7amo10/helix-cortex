package com.pulse.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing an asynchronous JAR file bytecode analysis job.
 * Caching is explicitly disabled due to large payloads and write-once semantics.
 */
@Entity
@Table(name = "jar_analysis")
@Cacheable(false)
public class JarAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "filename", length = 255, nullable = false)
    private String filename;

    @Column(name = "engineer_id", length = 100)
    private String engineerId;

    @Column(name = "class_count", nullable = false)
    private int classCount;

    @Column(name = "total_opcodes", nullable = false)
    private long totalOpcodes;

    @Column(name = "antipattern_count", nullable = false)
    private int antipatternCount;

    @Lob
    @Column(name = "dependency_graph_json", columnDefinition = "TEXT")
    private String dependencyGraphJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AnalysisStatus status;

    @Version
    @Column(name = "version")
    private Long version;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "jarAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnalysisClassMetric> classMetrics = new ArrayList<>();

    public JarAnalysis() {
        this.status = AnalysisStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    public JarAnalysis(String filename, String engineerId) {
        this();
        this.filename = filename;
        this.engineerId = engineerId;
    }

    public void addClassMetric(AnalysisClassMetric metric) {
        classMetrics.add(metric);
        metric.setJarAnalysis(this);
    }

    public void removeClassMetric(AnalysisClassMetric metric) {
        classMetrics.remove(metric);
        metric.setJarAnalysis(null);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getEngineerId() {
        return engineerId;
    }

    public void setEngineerId(String engineerId) {
        this.engineerId = engineerId;
    }

    public int getClassCount() {
        return classCount;
    }

    public void setClassCount(int classCount) {
        this.classCount = classCount;
    }

    public long getTotalOpcodes() {
        return totalOpcodes;
    }

    public void setTotalOpcodes(long totalOpcodes) {
        this.totalOpcodes = totalOpcodes;
    }

    public int getAntipatternCount() {
        return antipatternCount;
    }

    public void setAntipatternCount(int antipatternCount) {
        this.antipatternCount = antipatternCount;
    }

    public String getDependencyGraphJson() {
        return dependencyGraphJson;
    }

    public void setDependencyGraphJson(String dependencyGraphJson) {
        this.dependencyGraphJson = dependencyGraphJson;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public void setStatus(AnalysisStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public List<AnalysisClassMetric> getClassMetrics() {
        return classMetrics;
    }

    public void setClassMetrics(List<AnalysisClassMetric> classMetrics) {
        this.classMetrics = classMetrics;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JarAnalysis that = (JarAnalysis) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
