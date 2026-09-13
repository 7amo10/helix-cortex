package com.pulse.entity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSessionTest {

    @Test
    @DisplayName("SessionStatus enum should contain COMPILED, EXECUTED, and FAILED")
    void testSessionStatusEnum() {
        assertThat(SessionStatus.values()).containsExactlyInAnyOrder(
                SessionStatus.COMPILED,
                SessionStatus.EXECUTED,
                SessionStatus.FAILED
        );
    }

    @Test
    @DisplayName("RuleSession entity annotations: @Entity, @Table(name='rule_session'), @Cacheable(true), index on engineer_id")
    void testRuleSessionAnnotations() {
        Entity entity = RuleSession.class.getAnnotation(Entity.class);
        assertThat(entity).isNotNull();

        Table table = RuleSession.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("rule_session");

        Index[] indexes = table.indexes();
        assertThat(indexes).hasSize(1);
        assertThat(indexes[0].name()).isEqualTo("idx_rule_session_engineer");
        assertThat(indexes[0].columnList()).isEqualTo("engineer_id");

        Cacheable cacheable = RuleSession.class.getAnnotation(Cacheable.class);
        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).isTrue();
    }

    @Test
    @DisplayName("RuleSession fields validation: id, engineerId, ruleJson, compiledRuleId, status, version, createdAt, metrics")
    void testRuleSessionFields() throws NoSuchFieldException {
        Field idField = RuleSession.class.getDeclaredField("id");
        assertThat(idField.getAnnotation(Id.class)).isNotNull();
        assertThat(idField.getAnnotation(GeneratedValue.class).strategy()).isEqualTo(GenerationType.IDENTITY);

        Field engineerIdField = RuleSession.class.getDeclaredField("engineerId");
        Column engCol = engineerIdField.getAnnotation(Column.class);
        assertThat(engCol).isNotNull();
        assertThat(engCol.name()).isEqualTo("engineer_id");
        assertThat(engCol.length()).isEqualTo(100);

        Field ruleJsonField = RuleSession.class.getDeclaredField("ruleJson");
        Column rjCol = ruleJsonField.getAnnotation(Column.class);
        assertThat(rjCol).isNotNull();
        assertThat(rjCol.columnDefinition()).isEqualTo("TEXT");

        Field versionField = RuleSession.class.getDeclaredField("version");
        assertThat(versionField.getAnnotation(Version.class)).isNotNull();
        assertThat(versionField.getType()).isEqualTo(Long.class);

        Field metricsField = RuleSession.class.getDeclaredField("metrics");
        OneToMany otm = metricsField.getAnnotation(OneToMany.class);
        assertThat(otm).isNotNull();
        assertThat(otm.mappedBy()).isEqualTo("session");
        assertThat(otm.orphanRemoval()).isTrue();
        assertThat(Arrays.asList(otm.cascade())).contains(CascadeType.ALL);

        BatchSize batchSize = metricsField.getAnnotation(BatchSize.class);
        assertThat(batchSize).isNotNull();
        assertThat(batchSize.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("OpcodeMetric entity annotations and fields")
    void testOpcodeMetricAnnotations() throws NoSuchFieldException {
        Entity entity = OpcodeMetric.class.getAnnotation(Entity.class);
        assertThat(entity).isNotNull();

        Table table = OpcodeMetric.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("opcode_metric");

        Field sessionField = OpcodeMetric.class.getDeclaredField("session");
        ManyToOne mto = sessionField.getAnnotation(ManyToOne.class);
        assertThat(mto).isNotNull();
        assertThat(mto.fetch()).isEqualTo(FetchType.LAZY);

        JoinColumn jc = sessionField.getAnnotation(JoinColumn.class);
        assertThat(jc).isNotNull();
        assertThat(jc.name()).isEqualTo("session_id");
        assertThat(jc.nullable()).isFalse();

        Field totalOpcodeField = OpcodeMetric.class.getDeclaredField("totalOpcodeCount");
        assertThat(totalOpcodeField.getType()).isEqualTo(long.class);

        Field execTimeField = OpcodeMetric.class.getDeclaredField("executionTimeNanos");
        assertThat(execTimeField.getType()).isEqualTo(long.class);

        Field antipatternField = OpcodeMetric.class.getDeclaredField("antipatternFlag");
        assertThat(antipatternField.getType()).isEqualTo(boolean.class);
    }

    @Test
    @DisplayName("Bidirectional relationship between RuleSession and OpcodeMetric")
    void testBidirectionalRelationship() {
        RuleSession session = new RuleSession("engineer_1", "{\"rule\":\"x > 10\"}", "comp-123", SessionStatus.COMPILED);
        OpcodeMetric metric1 = new OpcodeMetric(150L, 45000L, false);
        OpcodeMetric metric2 = new OpcodeMetric(200L, 60000L, true);

        session.addMetric(metric1);
        session.addMetric(metric2);

        assertThat(session.getMetrics()).hasSize(2);
        assertThat(metric1.getSession()).isEqualTo(session);
        assertThat(metric2.getSession()).isEqualTo(session);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPILED);
        assertThat(session.getCreatedAt()).isNotNull();

        session.removeMetric(metric1);
        assertThat(session.getMetrics()).hasSize(1);
        assertThat(metric1.getSession()).isNull();
    }
}
