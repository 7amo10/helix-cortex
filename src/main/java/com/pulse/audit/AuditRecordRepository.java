package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Data access repository for {@link AuditRecord} JPA entities.
 */
@ApplicationScoped
public class AuditRecordRepository {

    @PersistenceContext
    private EntityManager em;

    public AuditRecordRepository() {}

    public AuditRecordRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Persists an audit record to the append-only table.
     *
     * @param record audit record to persist
     */
    @Transactional
    public void save(AuditRecord record) {
        em.persist(record);
    }

    /**
     * Finds an audit record by its unique monotonic sequence number.
     *
     * @param seq record sequence number
     * @return Optional containing the record if found
     */
    public Optional<AuditRecord> findBySeq(long seq) {
        TypedQuery<AuditRecord> query = em.createQuery(
                "SELECT r FROM AuditRecord r WHERE r.recordSeq = :seq", AuditRecord.class);
        query.setParameter("seq", seq);
        return query.getResultStream().findFirst();
    }

    /**
     * Retrieves the most recent audit records for a specific rule.
     *
     * @param ruleName name of the rule
     * @param limit    maximum number of records to return
     * @return list of audit records ordered descending by evaluatedAt
     */
    public List<AuditRecord> findByRuleName(String ruleName, int limit) {
        return em.createQuery(
                "SELECT r FROM AuditRecord r WHERE r.ruleName = :rn ORDER BY r.evaluatedAt DESC",
                AuditRecord.class)
                .setParameter("rn", ruleName)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Counts the total number of audit records in the database.
     *
     * @return total count
     */
    public long countAll() {
        return em.createQuery("SELECT COUNT(r) FROM AuditRecord r", Long.class)
                .getSingleResult();
    }

    /**
     * Finds the latest persisted audit record by highest sequence number.
     *
     * @return Optional containing the latest record if any exist
     */
    public Optional<AuditRecord> findLatest() {
        return em.createQuery(
                "SELECT r FROM AuditRecord r ORDER BY r.recordSeq DESC",
                AuditRecord.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
