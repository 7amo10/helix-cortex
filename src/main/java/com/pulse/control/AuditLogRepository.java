package com.pulse.control;

import com.pulse.entity.AuditLog;
import jakarta.ejb.Asynchronous;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Collections;
import java.util.List;

/**
 * Repository for persisting and querying security and access audit logs.
 * Supports asynchronous logging in an isolated REQUIRES_NEW transaction.
 */
@Stateless
public class AuditLogRepository {

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    public AuditLogRepository() {
    }

    public AuditLogRepository(EntityManager em) {
        this.em = em;
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void logAsync(String engineerId, String endpoint) {
        logAsync(engineerId, endpoint, "GET", true);
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void logAsync(String engineerId, String endpoint, String httpMethod, boolean success) {
        if (em != null) {
            AuditLog log = new AuditLog(engineerId, endpoint, httpMethod, success);
            em.persist(log);
        }
    }

    public AuditLog save(AuditLog log) {
        if (log == null) {
            throw new IllegalArgumentException("AuditLog cannot be null");
        }
        em.persist(log);
        return log;
    }

    public List<AuditLog> findByEngineerId(String engineerId) {
        if (engineerId == null || engineerId.isBlank()) {
            return Collections.emptyList();
        }
        return em.createQuery(
                        "SELECT a FROM AuditLog a WHERE a.engineerId = :engineerId ORDER BY a.timestamp DESC",
                        AuditLog.class)
                .setParameter("engineerId", engineerId)
                .getResultList();
    }

    public List<AuditLog> findAll() {
        return em.createQuery("SELECT a FROM AuditLog a ORDER BY a.timestamp DESC", AuditLog.class)
                .getResultList();
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
