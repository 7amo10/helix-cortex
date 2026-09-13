package com.pulse.control;

import com.pulse.entity.EngineRole;
import com.pulse.entity.EngineerAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineerAccountRepositoryTest {

    @Mock
    private EntityManager em;

    @Mock
    private TypedQuery<EngineerAccount> entityQuery;

    @Mock
    private TypedQuery<Long> countQuery;

    private EngineerAccountRepository repository;

    @BeforeEach
    void setUp() {
        repository = new EngineerAccountRepository(em);
    }

    @Test
    @DisplayName("save should call em.persist and return the entity")
    void testSave() {
        EngineerAccount account = new EngineerAccount("engineer_1", "hashed", EngineRole.ENGINEER);
        EngineerAccount saved = repository.save(account);

        verify(em, times(1)).persist(account);
        assertThat(saved).isSameAs(account);
    }

    @Test
    @DisplayName("findByUsername should return Optional with account when matching username is found")
    void testFindByUsernameFound() {
        String username = "engineer_1";
        EngineerAccount expected = new EngineerAccount(username, "hashed", EngineRole.ENGINEER);

        when(em.createQuery(eq("SELECT a FROM EngineerAccount a WHERE a.username = :username"), eq(EngineerAccount.class)))
                .thenReturn(entityQuery);
        when(entityQuery.setParameter(eq("username"), eq(username))).thenReturn(entityQuery);
        when(entityQuery.setMaxResults(1)).thenReturn(entityQuery);
        when(entityQuery.getResultList()).thenReturn(List.of(expected));

        Optional<EngineerAccount> result = repository.findByUsername(username);

        assertThat(result).isPresent().contains(expected);
        verify(entityQuery).setParameter("username", username);
    }

    @Test
    @DisplayName("findByUsername should return Optional.empty when user does not exist")
    void testFindByUsernameNotFound() {
        String username = "non_existent";

        when(em.createQuery(eq("SELECT a FROM EngineerAccount a WHERE a.username = :username"), eq(EngineerAccount.class)))
                .thenReturn(entityQuery);
        when(entityQuery.setParameter(eq("username"), eq(username))).thenReturn(entityQuery);
        when(entityQuery.setMaxResults(1)).thenReturn(entityQuery);
        when(entityQuery.getResultList()).thenReturn(Collections.emptyList());

        Optional<EngineerAccount> result = repository.findByUsername(username);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("existsByUsername should return true when account exists and false otherwise")
    void testExistsByUsername() {
        when(em.createQuery(eq("SELECT COUNT(a) FROM EngineerAccount a WHERE a.username = :username"), eq(Long.class)))
                .thenReturn(countQuery);
        when(countQuery.setParameter(eq("username"), eq("admin"))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);

        boolean exists = repository.existsByUsername("admin");
        assertThat(exists).isTrue();

        when(countQuery.setParameter(eq("username"), eq("missing"))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        boolean missingExists = repository.existsByUsername("missing");
        assertThat(missingExists).isFalse();
    }

    @Test
    @DisplayName("SQL injection payload should be safely parameterized and return empty")
    void testSqlInjectionPayloadSafe() {
        String maliciousPayload = "admin' OR '1'='1";

        when(em.createQuery(eq("SELECT a FROM EngineerAccount a WHERE a.username = :username"), eq(EngineerAccount.class)))
                .thenReturn(entityQuery);
        when(entityQuery.setParameter(eq("username"), eq(maliciousPayload))).thenReturn(entityQuery);
        when(entityQuery.setMaxResults(1)).thenReturn(entityQuery);
        when(entityQuery.getResultList()).thenReturn(Collections.emptyList());

        Optional<EngineerAccount> result = repository.findByUsername(maliciousPayload);

        assertThat(result).isEmpty();
        verify(entityQuery).setParameter("username", maliciousPayload);
    }
}
