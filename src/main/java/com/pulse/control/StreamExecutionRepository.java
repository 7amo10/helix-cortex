package com.pulse.control;

import com.pulse.entity.StreamExecutionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Data access repository for {@link StreamExecutionRecord} and associated metrics.
 * Implements JDBC batching with periodic flush and clear to optimize HikariCP connection pool usage,
 * and utilizes JPA EntityGraph fetch hints to eliminate N+1 select problems.
 */
@ApplicationScoped
public class StreamExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(StreamExecutionRepository.class);
    private static final int DEFAULT_BATCH_SIZE = 25;

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    public StreamExecutionRepository() {
    }

    public StreamExecutionRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Persists or merges a single stream execution record.
     *
     * @param record the record to save
     * @return persisted entity
     */
    @Transactional
    public StreamExecutionRecord save(StreamExecutionRecord record) {
        Objects.requireNonNull(record, "record cannot be null");
        if (record.getId() == null) {
            em.persist(record);
            return record;
        }
        return em.merge(record);
    }

    /**
     * High-throughput JDBC batch persistence of stream execution records.
     * Flushes and clears the persistence context periodically (every 25 records)
     * to keep the Hibernate session lightweight and maintain connection pool stability.
     *
     * @param records batch of records to persist
     * @return count of successfully persisted records
     */
    @Transactional
    public int saveBatch(List<StreamExecutionRecord> records) {
        return saveBatch(records, DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch persist with configurable batch size.
     *
     * @param records batch of records to persist
     * @param batchSize number of entities to accumulate before flush/clear
     * @return count of successfully persisted records
     */
    @Transactional
    public int saveBatch(List<StreamExecutionRecord> records, int batchSize) {
        if (records == null || records.isEmpty() || em == null) {
            return 0;
        }

        int targetBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        int count = 0;

        for (int i = 0; i < records.size(); i++) {
            StreamExecutionRecord record = records.get(i);
            if (record != null) {
                if (record.getId() == null) {
                    em.persist(record);
                } else {
                    em.merge(record);
                }
                count++;

                if ((count % targetBatchSize == 0) || (i == records.size() - 1)) {
                    em.flush();
                    em.clear();
                }
            }
        }

        log.debug("Batch persisted {} stream execution records (batch size {})", count, targetBatchSize);
        return count;
    }

    /**
     * Retrieves all stream execution records with metrics eagerly fetched via JPA EntityGraph.
     *
     * @return list of records ordered by persisted timestamp descending
     */
    public List<StreamExecutionRecord> findAllWithMetrics() {
        if (em == null) {
            return Collections.emptyList();
        }
        EntityGraph<StreamExecutionRecord> entityGraph = em.createEntityGraph(StreamExecutionRecord.class);
        entityGraph.addAttributeNodes("metrics");
        return em.createQuery(
                "SELECT DISTINCT r FROM StreamExecutionRecord r LEFT JOIN FETCH r.metrics ORDER BY r.persistedAt DESC",
                StreamExecutionRecord.class)
                .setHint("jakarta.persistence.fetchgraph", entityGraph)
                .getResultList();
    }

    /**
     * Finds a record by database primary key using JPA EntityGraph.
     *
     * @param id record ID
     * @return Optional containing matching record if found
     */
    public Optional<StreamExecutionRecord> findById(Long id) {
        if (id == null || em == null) {
            return Optional.empty();
        }
        EntityGraph<StreamExecutionRecord> entityGraph = em.createEntityGraph(StreamExecutionRecord.class);
        entityGraph.addAttributeNodes("metrics");
        List<StreamExecutionRecord> list = em.createQuery(
                "SELECT DISTINCT r FROM StreamExecutionRecord r LEFT JOIN FETCH r.metrics WHERE r.id = :id",
                StreamExecutionRecord.class)
                .setParameter("id", id)
                .setHint("jakarta.persistence.fetchgraph", entityGraph)
                .getResultList();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Finds a record by unique event identifier using JPA EntityGraph.
     *
     * @param eventId unique event ID
     * @return Optional containing matching record if found
     */
    public Optional<StreamExecutionRecord> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || em == null) {
            return Optional.empty();
        }
        EntityGraph<StreamExecutionRecord> entityGraph = em.createEntityGraph(StreamExecutionRecord.class);
        entityGraph.addAttributeNodes("metrics");
        List<StreamExecutionRecord> list = em.createQuery(
                "SELECT DISTINCT r FROM StreamExecutionRecord r LEFT JOIN FETCH r.metrics WHERE r.eventId = :eventId",
                StreamExecutionRecord.class)
                .setParameter("eventId", eventId)
                .setHint("jakarta.persistence.fetchgraph", entityGraph)
                .getResultList();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Finds records by Kafka topic using JPA EntityGraph.
     *
     * @param topic Kafka topic
     * @return list of matching records
     */
    public List<StreamExecutionRecord> findByTopic(String topic) {
        if (topic == null || topic.isBlank() || em == null) {
            return Collections.emptyList();
        }
        EntityGraph<StreamExecutionRecord> entityGraph = em.createEntityGraph(StreamExecutionRecord.class);
        entityGraph.addAttributeNodes("metrics");
        return em.createQuery(
                "SELECT DISTINCT r FROM StreamExecutionRecord r LEFT JOIN FETCH r.metrics WHERE r.topic = :topic ORDER BY r.persistedAt DESC",
                StreamExecutionRecord.class)
                .setParameter("topic", topic)
                .setHint("jakarta.persistence.fetchgraph", entityGraph)
                .getResultList();
    }

    /**
     * Counts total persisted stream execution records.
     *
     * @return total count
     */
    public long count() {
        if (em == null) {
            return 0L;
        }
        return em.createQuery("SELECT COUNT(r) FROM StreamExecutionRecord r", Long.class)
                .getSingleResult();
    }

    /**
     * Deletes all records and child metrics (primarily for test fixture cleanup).
     */
    @Transactional
    public void deleteAll() {
        if (em != null) {
            em.createQuery("DELETE FROM StreamRecordMetric").executeUpdate();
            em.createQuery("DELETE FROM StreamExecutionRecord").executeUpdate();
        }
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
