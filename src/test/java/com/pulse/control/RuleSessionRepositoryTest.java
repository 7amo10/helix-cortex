package com.pulse.control;

import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleSessionRepositoryTest {

    @Mock
    private EntityManager em;

    @Mock
    private TypedQuery<RuleSession> typedQuery;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private CriteriaQuery<RuleSession> criteriaQuery;

    @Mock
    private Root<RuleSession> root;

    @Mock
    private Join<RuleSession, OpcodeMetric> joinMetric;

    @Mock
    private EntityGraph<RuleSession> entityGraph;

    private RuleSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RuleSessionRepository(em);
    }

    @Test
    @DisplayName("findAll executes JPQL query and returns result list")
    void testFindAll() {
        when(em.createQuery(anyString(), eq(RuleSession.class))).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        List<RuleSession> result = repository.findAll();
        assertThat(result).isEmpty();

        verify(em).createQuery("SELECT s FROM RuleSession s ORDER BY s.createdAt DESC", RuleSession.class);
    }

    @Test
    @DisplayName("findById returns Optional containing entity when found")
    void testFindById() {
        RuleSession session = new RuleSession("eng_1", "{}", "r-1", SessionStatus.COMPILED);
        when(em.find(RuleSession.class, 1L)).thenReturn(session);

        Optional<RuleSession> opt = repository.findById(1L);
        assertThat(opt).isPresent();
        assertThat(opt.get().getEngineerId()).isEqualTo("eng_1");

        Optional<RuleSession> nullOpt = repository.findById(null);
        assertThat(nullOpt).isEmpty();
    }

    @Test
    @DisplayName("findByEngineerId binds named parameter :engineerId")
    void testFindByEngineerId() {
        RuleSession session = new RuleSession("engineer_1", "{}", "r-1", SessionStatus.COMPILED);
        when(em.createQuery(anyString(), eq(RuleSession.class))).thenReturn(typedQuery);
        when(typedQuery.setParameter("engineerId", "engineer_1")).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(session));

        List<RuleSession> result = repository.findByEngineerId("engineer_1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEngineerId()).isEqualTo("engineer_1");

        verify(typedQuery).setParameter("engineerId", "engineer_1");
    }

    @Test
    @DisplayName("findByEngineerId with injection string safely executes as parameterized query")
    void testFindByEngineerIdInjectionSafety() {
        String injectionAttempt = "' OR 1=1 --";
        when(em.createQuery(anyString(), eq(RuleSession.class))).thenReturn(typedQuery);
        when(typedQuery.setParameter("engineerId", injectionAttempt)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        List<RuleSession> result = repository.findByEngineerId(injectionAttempt);
        assertThat(result).isEmpty();

        verify(typedQuery).setParameter("engineerId", injectionAttempt);
    }

    @Test
    @DisplayName("save calls persist for transient entity and merge for detached entity")
    void testSave() throws Exception {
        RuleSession newSession = new RuleSession("eng_1", "{}", "r-1", SessionStatus.COMPILED);
        RuleSession saved = repository.save(newSession);
        verify(em).persist(newSession);
        assertThat(saved).isSameAs(newSession);

        // Reflectively set ID to simulate detached entity
        var idField = RuleSession.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(newSession, 100L);

        when(em.merge(newSession)).thenReturn(newSession);
        RuleSession merged = repository.save(newSession);
        verify(em).merge(newSession);
        assertThat(merged).isSameAs(newSession);
    }

    @Test
    @DisplayName("updateStatus finds entity and updates its status within active transaction")
    void testUpdateStatus() {
        RuleSession session = new RuleSession("eng_1", "{}", "r-1", SessionStatus.COMPILED);
        when(em.find(RuleSession.class, 1L)).thenReturn(session);

        Optional<RuleSession> updated = repository.updateStatus(1L, SessionStatus.EXECUTED);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(SessionStatus.EXECUTED);
    }

    @Test
    @DisplayName("findHighOpcodeDensitySessions constructs Criteria API query and sets EntityGraph hint")
    void testFindHighOpcodeDensitySessions() {
        when(em.getCriteriaBuilder()).thenReturn(criteriaBuilder);
        when(criteriaBuilder.createQuery(RuleSession.class)).thenReturn(criteriaQuery);
        when(criteriaQuery.from(RuleSession.class)).thenReturn(root);
        when(root.<RuleSession, OpcodeMetric>join("metrics")).thenReturn(joinMetric);
        when(criteriaQuery.select(root)).thenReturn(criteriaQuery);
        when(criteriaQuery.distinct(true)).thenReturn(criteriaQuery);

        Path<Object> countPath = mock(Path.class);
        when(joinMetric.get("totalOpcodeCount")).thenReturn(countPath);
        Predicate gtPredicate = mock(Predicate.class);
        when(criteriaBuilder.gt(any(Expression.class), eq(500L))).thenReturn(gtPredicate);
        when(criteriaQuery.where(gtPredicate)).thenReturn(criteriaQuery);

        Order descOrder = mock(Order.class);
        when(criteriaBuilder.desc(countPath)).thenReturn(descOrder);
        when(criteriaQuery.orderBy(descOrder)).thenReturn(criteriaQuery);

        when(em.createEntityGraph(RuleSession.class)).thenReturn(entityGraph);
        when(em.createQuery(criteriaQuery)).thenReturn(typedQuery);
        when(typedQuery.setHint("jakarta.persistence.fetchgraph", entityGraph)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        List<RuleSession> sessions = repository.findHighOpcodeDensitySessions(500L);
        assertThat(sessions).isEmpty();

        verify(entityGraph).addAttributeNodes("metrics");
        verify(typedQuery).setHint("jakarta.persistence.fetchgraph", entityGraph);
    }
}
