package com.pulse.control;

import com.pulse.entity.StreamExecutionRecord;
import com.pulse.entity.StreamRecordMetric;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Task: Stream Execution Repository Unit Tests")
class StreamExecutionRepositoryTest {

    private EntityManager em;
    private StreamExecutionRepository repository;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        repository = new StreamExecutionRepository(em);
    }

    @Test
    @DisplayName("Should persist new StreamExecutionRecord when id is null")
    void testSaveNewRecord() {
        StreamExecutionRecord record = new StreamExecutionRecord(
                "evt-1", "rules.input", "TestRule", true, "OK", null, 1000L);

        StreamExecutionRecord saved = repository.save(record);

        assertThat(saved).isEqualTo(record);
        verify(em, times(1)).persist(record);
    }

    @Test
    @DisplayName("Should merge existing StreamExecutionRecord when id is not null")
    void testMergeExistingRecord() {
        StreamExecutionRecord record = new StreamExecutionRecord(
                "evt-1", "rules.input", "TestRule", true, "OK", null, 1000L);
        record.setId(10L);

        when(em.merge(record)).thenReturn(record);

        StreamExecutionRecord saved = repository.save(record);

        assertThat(saved).isEqualTo(record);
        verify(em, times(1)).merge(record);
    }

    @Test
    @DisplayName("Should batch persist records with periodic flush and clear every 25 records")
    void testSaveBatchFlushesPeriodically() {
        List<StreamExecutionRecord> records = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            records.add(new StreamExecutionRecord("evt-" + i, "rules.input", "Rule", true, "OK", null, 500L));
        }

        int count = repository.saveBatch(records, 25);

        assertThat(count).isEqualTo(60);
        verify(em, times(60)).persist(any(StreamExecutionRecord.class));
        // Flush and clear should occur at record 25, 50, and 60 (end of batch) -> 3 times
        verify(em, times(3)).flush();
        verify(em, times(3)).clear();
    }

    @Test
    @DisplayName("Should find record by event ID using JPA EntityGraph")
    @SuppressWarnings("unchecked")
    void testFindByEventIdWithEntityGraph() {
        EntityGraph<StreamExecutionRecord> entityGraph = mock(EntityGraph.class);
        TypedQuery<StreamExecutionRecord> query = mock(TypedQuery.class);

        when(em.createEntityGraph(StreamExecutionRecord.class)).thenReturn(entityGraph);
        when(em.createQuery(anyString(), eq(StreamExecutionRecord.class))).thenReturn(query);
        when(query.setParameter(eq("eventId"), anyString())).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.fetchgraph"), any())).thenReturn(query);

        StreamExecutionRecord record = new StreamExecutionRecord("evt-xyz", "topic", "Rule", true, "OK", null, 100L);
        record.addMetric("latency", 100.0, "100");
        when(query.getResultList()).thenReturn(List.of(record));

        Optional<StreamExecutionRecord> result = repository.findByEventId("evt-xyz");

        assertThat(result).isPresent();
        assertThat(result.get().getEventId()).isEqualTo("evt-xyz");
        verify(entityGraph, times(1)).addAttributeNodes("metrics");
        verify(query, times(1)).setHint("jakarta.persistence.fetchgraph", entityGraph);
    }

    @Test
    @DisplayName("Should find all records with metrics using JPA EntityGraph")
    @SuppressWarnings("unchecked")
    void testFindAllWithMetricsUsesEntityGraph() {
        EntityGraph<StreamExecutionRecord> entityGraph = mock(EntityGraph.class);
        TypedQuery<StreamExecutionRecord> query = mock(TypedQuery.class);

        when(em.createEntityGraph(StreamExecutionRecord.class)).thenReturn(entityGraph);
        when(em.createQuery(anyString(), eq(StreamExecutionRecord.class))).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.fetchgraph"), any())).thenReturn(query);

        StreamExecutionRecord r1 = new StreamExecutionRecord("e1", "t", "R", true, "OK", null, 10L);
        when(query.getResultList()).thenReturn(List.of(r1));

        List<StreamExecutionRecord> records = repository.findAllWithMetrics();

        assertThat(records).hasSize(1);
        verify(entityGraph, times(1)).addAttributeNodes("metrics");
        verify(query, times(1)).setHint("jakarta.persistence.fetchgraph", entityGraph);
    }
}
