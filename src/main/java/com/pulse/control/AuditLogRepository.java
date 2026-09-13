package com.pulse.control;

import jakarta.ejb.Asynchronous;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Repository for recording security and access audit log entries.
 * Executes asynchronously in an independent REQUIRES_NEW transaction.
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
        // Will persist to database once AuditLog entity is active (Task 1.9)
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
