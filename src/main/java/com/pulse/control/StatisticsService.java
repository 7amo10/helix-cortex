package com.pulse.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Service providing access to Hibernate runtime statistics to verify
 * statement execution counts and ensure elimination of N+1 select queries.
 */
@ApplicationScoped
public class StatisticsService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsService.class);

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    public StatisticsService() {
    }

    public StatisticsService(EntityManager em) {
        this.em = em;
    }

    public Statistics getStatistics() {
        if (em == null) {
            return null;
        }
        try {
            Session session = em.unwrap(Session.class);
            return session.getSessionFactory().getStatistics();
        } catch (Exception e) {
            log.debug("Hibernate Session or Statistics not available: {}", e.getMessage());
            return null;
        }
    }

    public long getPrepareStatementCount() {
        Statistics stats = getStatistics();
        return stats != null ? stats.getPrepareStatementCount() : 0L;
    }

    public void clearStatistics() {
        Statistics stats = getStatistics();
        if (stats != null) {
            stats.clear();
        }
    }

    /**
     * Executes an operation while logging the number of prepared statements executed.
     *
     * @param operationName human-readable name of the monitored operation
     * @param operation     supplier lambda
     * @param <T>           return type
     * @return operation result
     */
    public <T> T measureStatements(String operationName, Supplier<T> operation) {
        long before = getPrepareStatementCount();
        try {
            return operation.get();
        } finally {
            long after = getPrepareStatementCount();
            long executed = after - before;
            log.info("[Hibernate Statistics] Operation '{}' executed {} prepared SQL statement(s)", operationName, executed);
        }
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
