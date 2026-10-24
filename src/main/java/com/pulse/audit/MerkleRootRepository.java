package com.pulse.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * Data access repository for {@link MerkleRoot} JPA entities.
 */
@ApplicationScoped
public class MerkleRootRepository {

    @PersistenceContext
    private EntityManager em;

    public MerkleRootRepository() {}

    public MerkleRootRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Persists a published Merkle root checkpoint to the database.
     *
     * @param root Merkle root entity
     */
    @Transactional
    public void save(MerkleRoot root) {
        em.persist(root);
    }

    /**
     * Finds the most recently published Merkle root checkpoint.
     *
     * @return Optional containing the latest MerkleRoot
     */
    public Optional<MerkleRoot> findLatest() {
        return em.createQuery(
                "SELECT r FROM MerkleRoot r ORDER BY r.publishedAt DESC",
                MerkleRoot.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
