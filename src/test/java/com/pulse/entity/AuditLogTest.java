package com.pulse.entity;

import com.pulse.control.AuditLogRepository;
import jakarta.persistence.Cacheable;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogTest {

    @Mock
    private EntityManager em;

    @Mock
    private TypedQuery<AuditLog> query;

    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AuditLogRepository(em);
    }

    @Test
    @DisplayName("AuditLog should have @Cacheable(false) and idx_audit_engineer index on table")
    void testAuditLogAnnotations() {
        Cacheable cacheable = AuditLog.class.getAnnotation(Cacheable.class);
        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).isFalse();

        Table table = AuditLog.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("audit_log");
        assertThat(table.indexes()).hasSize(1);
        assertThat(table.indexes()[0].name()).isEqualTo("idx_audit_engineer");
    }

    @Test
    @DisplayName("timestamp should be automatically populated at construction")
    void testTimestampAutoPopulated() {
        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);
        AuditLog log = new AuditLog();
        Instant after = Instant.now().plus(1, ChronoUnit.SECONDS);

        assertThat(log.getTimestamp()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("logAsync should persist AuditLog in EntityManager")
    void testLogAsyncPersists() {
        repository.logAsync("alice_eng", "api/v1/rules/compile", "POST", true);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(em).persist(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getEngineerId()).isEqualTo("alice_eng");
        assertThat(saved.getEndpoint()).isEqualTo("api/v1/rules/compile");
        assertThat(saved.getHttpMethod()).isEqualTo("POST");
        assertThat(saved.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("findByEngineerId should return matching logs for given engineer")
    void testFindByEngineerId() {
        AuditLog sample = new AuditLog("alice_eng", "api/v1/rules/compile", "POST", true);
        when(em.createQuery(anyString(), eq(AuditLog.class))).thenReturn(query);
        when(query.setParameter(eq("engineerId"), eq("alice_eng"))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(sample));

        List<AuditLog> results = repository.findByEngineerId("alice_eng");

        assertThat(results).hasSize(1).contains(sample);
        verify(query).setParameter("engineerId", "alice_eng");
    }
}
