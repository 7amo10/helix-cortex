package com.pulse.entity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JarAnalysisTest {

    @Test
    @DisplayName("JarAnalysis has correct entity annotations, cacheable false, and table name")
    void testEntityAnnotations() {
        assertThat(JarAnalysis.class.isAnnotationPresent(jakarta.persistence.Entity.class)).isTrue();
        Table table = JarAnalysis.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("jar_analysis");

        Cacheable cacheable = JarAnalysis.class.getAnnotation(Cacheable.class);
        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).isFalse();
    }

    @Test
    @DisplayName("JarAnalysis version field is Long and annotated with @Version")
    void testVersionField() throws NoSuchFieldException {
        Field versionField = JarAnalysis.class.getDeclaredField("version");
        assertThat(versionField.getType()).isEqualTo(Long.class);
        assertThat(versionField.isAnnotationPresent(Version.class)).isTrue();
    }

    @Test
    @DisplayName("classMetrics has @OneToMany and @BatchSize(size=20)")
    void testClassMetricsMapping() throws NoSuchFieldException {
        Field classMetricsField = JarAnalysis.class.getDeclaredField("classMetrics");
        assertThat(classMetricsField.isAnnotationPresent(OneToMany.class)).isTrue();
        OneToMany oneToMany = classMetricsField.getAnnotation(OneToMany.class);
        assertThat(oneToMany.mappedBy()).isEqualTo("jarAnalysis");

        BatchSize batchSize = classMetricsField.getAnnotation(BatchSize.class);
        assertThat(batchSize).isNotNull();
        assertThat(batchSize.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("Default constructor initializes status to PENDING and submittedAt to non-null")
    void testDefaultValues() {
        JarAnalysis analysis = new JarAnalysis("test.jar", "eng_42");
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(analysis.getSubmittedAt()).isNotNull();
        assertThat(analysis.getSubmittedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(analysis.getFilename()).isEqualTo("test.jar");
        assertThat(analysis.getEngineerId()).isEqualTo("eng_42");
        assertThat(analysis.getClassMetrics()).isEmpty();
    }

    @Test
    @DisplayName("addClassMetric and removeClassMetric manage bidirectional relationship correctly")
    void testBidirectionalRelationship() {
        JarAnalysis analysis = new JarAnalysis("app.jar", "engineer_1");
        AnalysisClassMetric metric1 = new AnalysisClassMetric("com.test.Service", 45, false, null);
        AnalysisClassMetric metric2 = new AnalysisClassMetric("com.test.Util", 120, true, null);

        analysis.addClassMetric(metric1);
        analysis.addClassMetric(metric2);

        assertThat(analysis.getClassMetrics()).hasSize(2);
        assertThat(metric1.getJarAnalysis()).isSameAs(analysis);
        assertThat(metric2.getJarAnalysis()).isSameAs(analysis);

        analysis.removeClassMetric(metric1);
        assertThat(analysis.getClassMetrics()).hasSize(1);
        assertThat(metric1.getJarAnalysis()).isNull();
        assertThat(analysis.getClassMetrics()).containsExactly(metric2);
    }

    @Test
    @DisplayName("equals and hashCode contract based on ID")
    void testEqualsAndHashCode() {
        JarAnalysis ja1 = new JarAnalysis();
        ja1.setId(10L);
        JarAnalysis ja2 = new JarAnalysis();
        ja2.setId(10L);
        JarAnalysis ja3 = new JarAnalysis();
        ja3.setId(20L);

        assertThat(ja1).isEqualTo(ja2);
        assertThat(ja1.hashCode()).isEqualTo(ja2.hashCode());
        assertThat(ja1).isNotEqualTo(ja3);

        AnalysisClassMetric cm1 = new AnalysisClassMetric();
        cm1.setId(5L);
        AnalysisClassMetric cm2 = new AnalysisClassMetric();
        cm2.setId(5L);
        assertThat(cm1).isEqualTo(cm2);
        assertThat(cm1.hashCode()).isEqualTo(cm2.hashCode());
    }
}
