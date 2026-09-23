package com.pulse.control;

import com.pulse.entity.MlModel;
import jakarta.enterprise.context.ApplicationScoped;
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
 * Data access repository for {@link MlModel} entities in the Helix Cortex model registry.
 *
 * <p>Enforces strictly parameterized JPQL queries to prevent SQL injection, supports versioned
 * model queries, active model resolution, and atomic bulk deactivation for version switching.</p>
 */
@ApplicationScoped
public class MlModelRepository {

    private static final Logger log = LoggerFactory.getLogger(MlModelRepository.class);

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    public MlModelRepository() {
    }

    public MlModelRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Persists a new model or merges changes on an existing model entity.
     *
     * @param model model entity to save
     * @return persisted/merged entity
     * @throws IllegalArgumentException if model is null
     */
    @Transactional
    public MlModel save(MlModel model) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }
        if (model.getId() == null) {
            em.persist(model);
            return model;
        }
        return em.merge(model);
    }

    /**
     * Finds a model by its primary key ID.
     *
     * @param id primary key identifier
     * @return Optional containing the model if found
     */
    public Optional<MlModel> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(MlModel.class, id));
    }

    /**
     * Finds the currently active model version for a given model name.
     *
     * @param modelName unique name of the model
     * @return Optional containing the active model descriptor if present
     */
    public Optional<MlModel> findActiveByName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        List<MlModel> results = em.createQuery(
                        "SELECT m FROM MlModel m WHERE m.modelName = :modelName AND m.active = true",
                        MlModel.class)
                .setParameter("modelName", modelName)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Finds a specific model version by model name and version string.
     *
     * @param modelName model name
     * @param version   version string
     * @return Optional containing matching model if found
     */
    public Optional<MlModel> findByModelNameAndVersion(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        List<MlModel> results = em.createQuery(
                        "SELECT m FROM MlModel m WHERE m.modelName = :modelName AND m.version = :version",
                        MlModel.class)
                .setParameter("modelName", modelName)
                .setParameter("version", version)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Lists the complete version history for a given model name, ordered newest first.
     *
     * @param modelName unique name of the model
     * @return list of model versions ordered by uploadedAt DESC
     */
    public List<MlModel> findVersionHistory(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Collections.emptyList();
        }
        return em.createQuery(
                        "SELECT m FROM MlModel m WHERE m.modelName = :modelName ORDER BY m.uploadedAt DESC",
                        MlModel.class)
                .setParameter("modelName", modelName)
                .getResultList();
    }

    /**
     * Finds all currently active models across the entire registry.
     *
     * @return list of active models ordered by model name
     */
    public List<MlModel> findAllActiveModels() {
        return em.createQuery(
                        "SELECT m FROM MlModel m WHERE m.active = true ORDER BY m.modelName ASC",
                        MlModel.class)
                .getResultList();
    }

    /**
     * Finds all registered models, active and inactive, ordered by name and version.
     *
     * @return list of all models
     */
    public List<MlModel> findAll() {
        return em.createQuery(
                        "SELECT m FROM MlModel m ORDER BY m.modelName ASC, m.uploadedAt DESC",
                        MlModel.class)
                .getResultList();
    }

    /**
     * Checks if a model with the given name and version already exists.
     *
     * @param modelName model name
     * @param version   version string
     * @return true if model version exists
     */
    public boolean existsByModelNameAndVersion(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            return false;
        }
        Long count = em.createQuery(
                        "SELECT COUNT(m) FROM MlModel m WHERE m.modelName = :modelName AND m.version = :version",
                        Long.class)
                .setParameter("modelName", modelName)
                .setParameter("version", version)
                .getSingleResult();
        return count != null && count > 0;
    }

    /**
     * Deactivates all versions of a specific model name.
     * Used prior to activating a target version to ensure only one active version per model.
     *
     * @param modelName model name
     * @return count of updated model records
     */
    @Transactional
    public int deactivateAllVersions(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        return em.createQuery(
                        "UPDATE MlModel m SET m.active = false WHERE m.modelName = :modelName")
                .setParameter("modelName", modelName)
                .executeUpdate();
    }

    /**
     * Deletes a model from the registry.
     *
     * @param model model entity to delete
     */
    @Transactional
    public void delete(MlModel model) {
        if (model != null) {
            em.remove(em.contains(model) ? model : em.merge(model));
        }
    }

    /**
     * Deletes a model by primary key identifier.
     *
     * @param id primary key identifier
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean deleteById(Long id) {
        if (id == null) {
            return false;
        }
        MlModel model = em.find(MlModel.class, id);
        if (model != null) {
            em.remove(model);
            return true;
        }
        return false;
    }

    /**
     * Returns the total count of registered models across all versions.
     *
     * @return total count
     */
    public long count() {
        Long count = em.createQuery("SELECT COUNT(m) FROM MlModel m", Long.class)
                .getSingleResult();
        return count != null ? count : 0L;
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }
}
