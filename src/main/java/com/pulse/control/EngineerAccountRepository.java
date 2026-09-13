package com.pulse.control;

import com.pulse.entity.EngineerAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository managing EngineerAccount entities.
 * Strictly enforces parameterized JPQL queries without string concatenation.
 */
@ApplicationScoped
public class EngineerAccountRepository {

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    public EngineerAccountRepository() {
    }

    public EngineerAccountRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Finds an engineer account by unique username using named parameters.
     *
     * @param username the account username
     * @return Optional containing the EngineerAccount if found
     */
    public Optional<EngineerAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        List<EngineerAccount> results = em.createQuery(
                        "SELECT a FROM EngineerAccount a WHERE a.username = :username", EngineerAccount.class)
                .setParameter("username", username)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Persists a new engineer account and returns the managed entity.
     *
     * @param account the entity to persist
     * @return the managed entity
     */
    public EngineerAccount save(EngineerAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        em.persist(account);
        return account;
    }

    /**
     * Checks if an account exists with the given username using named parameters.
     *
     * @param username username to verify
     * @return true if an account exists, false otherwise
     */
    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        Long count = em.createQuery(
                        "SELECT COUNT(a) FROM EngineerAccount a WHERE a.username = :username", Long.class)
                .setParameter("username", username)
                .getSingleResult();
        return count != null && count > 0;
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
