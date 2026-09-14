package com.pulse.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * Child entity representing class-level opcode and antipattern metrics within a JAR analysis.
 */
@Entity
@Table(name = "analysis_class_metric")
public class AnalysisClassMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_name", length = 500, nullable = false)
    private String className;

    @Column(name = "opcode_count", nullable = false)
    private int opcodeCount;

    @Column(name = "has_antipattern", nullable = false)
    private boolean hasAntipattern;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jar_analysis_id", nullable = false)
    private JarAnalysis jarAnalysis;

    public AnalysisClassMetric() {
    }

    public AnalysisClassMetric(String className, int opcodeCount, boolean hasAntipattern, JarAnalysis jarAnalysis) {
        this.className = className;
        this.opcodeCount = opcodeCount;
        this.hasAntipattern = hasAntipattern;
        this.jarAnalysis = jarAnalysis;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public int getOpcodeCount() {
        return opcodeCount;
    }

    public void setOpcodeCount(int opcodeCount) {
        this.opcodeCount = opcodeCount;
    }

    public boolean isHasAntipattern() {
        return hasAntipattern;
    }

    public void setHasAntipattern(boolean hasAntipattern) {
        this.hasAntipattern = hasAntipattern;
    }

    public JarAnalysis getJarAnalysis() {
        return jarAnalysis;
    }

    public void setJarAnalysis(JarAnalysis jarAnalysis) {
        this.jarAnalysis = jarAnalysis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnalysisClassMetric that = (AnalysisClassMetric) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
