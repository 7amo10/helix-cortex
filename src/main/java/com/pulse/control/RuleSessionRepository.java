package com.pulse.control;

import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Data access repository for {@link RuleSession} and child metrics.
 * Utilizes strictly parameterized JPQL queries, Criteria API for high-density session analytics,
 * and EntityGraph fetch hints to prevent N+1 select problems.
 */
@ApplicationScoped
public class RuleSessionRepository {

    @PersistenceContext
    private EntityManager em;

    public RuleSessionRepository() {
    }

    public RuleSessionRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Retrieves all rule sessions.
     *
     * @return list of all sessions
     */
    public List<RuleSession> findAll() {
        return em.createQuery("SELECT s FROM RuleSession s ORDER BY s.createdAt DESC", RuleSession.class)
                .getResultList();
    }

    /**
     * Finds a session by its unique database identifier.
     *
     * @param id session ID
     * @return Optional containing session if found
     */
    public Optional<RuleSession> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(RuleSession.class, id));
    }

    /**
     * Finds sessions associated with a specific engineer identifier using parameterized JPQL.
     *
     * @param engineerId engineer username or identifier
     * @return list of matching sessions
     */
    public List<RuleSession> findByEngineerId(String engineerId) {
        return em.createQuery("SELECT s FROM RuleSession s WHERE s.engineerId = :engineerId ORDER BY s.createdAt DESC", RuleSession.class)
                .setParameter("engineerId", engineerId)
                .getResultList();
    }

    /**
     * Criteria API query joining RuleSession to OpcodeMetric, filtering for totalOpcodeCount > threshold,
     * ordering by totalOpcodeCount DESC, and applying an EntityGraph fetchgraph hint to eagerly load metrics.
     *
     * @param threshold minimum total opcode count
     * @return sessions exceeding the opcode threshold
     */
    public List<RuleSession> findHighOpcodeDensitySessions(long threshold) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<RuleSession> cq = cb.createQuery(RuleSession.class);
        Root<RuleSession> session = cq.from(RuleSession.class);
        Join<RuleSession, OpcodeMetric> metric = session.join("metrics");

        cq.select(session)
                .distinct(true)
                .where(cb.gt(metric.get("totalOpcodeCount"), threshold))
                .orderBy(cb.desc(metric.get("totalOpcodeCount")));

        EntityGraph<RuleSession> entityGraph = em.createEntityGraph(RuleSession.class);
        entityGraph.addAttributeNodes("metrics");

        return em.createQuery(cq)
                .setHint("jakarta.persistence.fetchgraph", entityGraph)
                .getResultList();
    }

    /**
     * Persists or updates a session within the current transaction.
     *
     * @param session session to save
     * @return managed entity
     */
    public RuleSession save(RuleSession session) {
        Objects.requireNonNull(session, "session cannot be null");
        if (session.getId() == null) {
            em.persist(session);
            return session;
        }
        return em.merge(session);
    }

    /**
     * Updates the status of an existing session within the active transaction.
     *
     * @param id     session ID
     * @param status new lifecycle status
     * @return Optional containing updated session if found
     */
    public Optional<RuleSession> updateStatus(Long id, SessionStatus status) {
        if (id == null) {
            return Optional.empty();
        }
        RuleSession session = em.find(RuleSession.class, id);
        if (session != null) {
            session.setStatus(status);
        }
        return Optional.ofNullable(session);
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
