package com.pulse.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.entity.AnalysisClassMetric;
import com.pulse.entity.JarAnalysis;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Task 4.1: N+1 Optimization Acceptance Criteria Verification")
class NPlusOneOptimizationVerificationTest {

    @Mock
    private EntityManager em;

    @Mock
    private TypedQuery<RuleSession> typedQuery;

    @Mock
    private EntityGraph<RuleSession> entityGraph;

    private RuleSessionRepository ruleSessionRepository;

    @BeforeEach
    void setUp() {
        ruleSessionRepository = new RuleSessionRepository(em);
        lenient().when(em.createEntityGraph(RuleSession.class)).thenReturn(entityGraph);
        lenient().when(typedQuery.setHint(anyString(), any())).thenReturn(typedQuery);
    }

    @Test
    @DisplayName("Acceptance Criteria 1: Hibernate statistics logging enabled in persistence.xml")
    void testHibernateStatisticsLoggingEnabled() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/META-INF/persistence.xml")) {
            assertThat(is).isNotNull();
            String xmlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(xmlContent).contains("hibernate.generate_statistics");
            assertThat(xmlContent).contains("hibernate.statistics.statistics_enabled");
        }
    }

    @Test
    @DisplayName("Acceptance Criteria 2: findAll on RuleSession uses LEFT JOIN FETCH s.metrics")
    void testFindAllUsesJoinFetch() {
        when(em.createQuery(anyString(), eq(RuleSession.class))).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        ruleSessionRepository.findAll();

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(em).createQuery(queryCaptor.capture(), eq(RuleSession.class));
        assertThat(queryCaptor.getValue())
                .contains("LEFT JOIN FETCH s.metrics");
        verify(typedQuery).setHint(eq("jakarta.persistence.fetchgraph"), eq(entityGraph));
    }

    @Test
    @DisplayName("Acceptance Criteria 3: findHighOpcodeDensitySessions executes via EntityGraph fetch hint")
    void testFindHighOpcodeDensitySessionsUsesEntityGraph() {
        jakarta.persistence.criteria.CriteriaBuilder cb = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        jakarta.persistence.criteria.CriteriaQuery<RuleSession> cq = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.Root<RuleSession> root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.Join<RuleSession, OpcodeMetric> join = org.mockito.Mockito.mock(jakarta.persistence.criteria.Join.class);
        jakarta.persistence.criteria.Path<Object> path = org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.Predicate predicate = org.mockito.Mockito.mock(jakarta.persistence.criteria.Predicate.class);
        jakarta.persistence.criteria.Order descOrder = org.mockito.Mockito.mock(jakarta.persistence.criteria.Order.class);

        when(em.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createQuery(RuleSession.class)).thenReturn(cq);
        when(cq.from(RuleSession.class)).thenReturn(root);
        when(root.<RuleSession, OpcodeMetric>join("metrics")).thenReturn(join);
        when(cq.select(root)).thenReturn(cq);
        when(join.get("totalOpcodeCount")).thenReturn(path);
        when(cb.gt(any(jakarta.persistence.criteria.Expression.class), eq(10L))).thenReturn(predicate);
        when(cq.where(predicate)).thenReturn(cq);
        when(cb.desc(path)).thenReturn(descOrder);
        when(cq.orderBy(descOrder)).thenReturn(cq);
        when(em.createQuery(cq)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        ruleSessionRepository.findHighOpcodeDensitySessions(10L);

        verify(typedQuery).setHint("jakarta.persistence.fetchgraph", entityGraph);
        verify(entityGraph).addAttributeNodes("metrics");
    }

    @Test
    @DisplayName("Acceptance Criteria 4: JarAnalysis.classMetrics has @BatchSize(size = 20)")
    void testJarAnalysisClassMetricsBatchSize() throws NoSuchFieldException {
        Field classMetricsField = JarAnalysis.class.getDeclaredField("classMetrics");
        BatchSize batchSize = classMetricsField.getAnnotation(BatchSize.class);

        assertThat(batchSize).isNotNull();
        assertThat(batchSize.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("Acceptance Criteria 5: JSON serialization works without LazyInitializationException")
    void testJsonSerializationNoLazyInitializationException() {
        ObjectMapper mapper = new ObjectMapper();

        RuleSession session = new RuleSession("eng-001", "{\"rule\":\"x > 0\"}", "sample.rule", SessionStatus.EXECUTED);
        OpcodeMetric metric = new OpcodeMetric(15L, 3000L, false);
        session.addMetric(metric);

        JarAnalysis analysis = new JarAnalysis("test.jar", "eng-001");
        AnalysisClassMetric classMetric = new AnalysisClassMetric("com.test.MyClass", 100, false, analysis);
        analysis.addClassMetric(classMetric);

        assertThatCode(() -> {
            String sessionJson = mapper.writeValueAsString(session);
            assertThat(sessionJson).contains("eng-001");

            String analysisJson = mapper.writeValueAsString(analysis);
            assertThat(analysisJson).contains("test.jar");
        }).doesNotThrowAnyException();
    }
}
