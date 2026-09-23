package com.pulse.control;

import com.pulse.entity.MlModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MlModelRepositoryTest {

    @Mock
    private EntityManager em;

    @Mock
    private TypedQuery<MlModel> typedQuery;

    @Mock
    private TypedQuery<Long> countQuery;

    @Mock
    private Query updateQuery;

    private MlModelRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MlModelRepository(em);
        lenient().when(typedQuery.setParameter(anyString(), any())).thenReturn(typedQuery);
        lenient().when(typedQuery.setMaxResults(anyInt())).thenReturn(typedQuery);
        lenient().when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    }

    @Test
    @DisplayName("save should persist new model when ID is null")
    void testSaveNewModel() {
        MlModel model = new MlModel("fraud_model_v1", "1.0.0", "/path", 128, "{}", "admin", "desc");
        assertThat(model.getId()).isNull();

        MlModel saved = repository.save(model);

        verify(em).persist(model);
        verify(em, never()).merge(any());
        assertThat(saved).isSameAs(model);
    }

    @Test
    @DisplayName("save should throw IllegalArgumentException when model is null")
    void testSaveNullModel() {
        assertThatThrownBy(() -> repository.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model cannot be null");
    }

    @Test
    @DisplayName("findById returns Optional containing entity when found")
    void testFindById() {
        MlModel model = new MlModel("fraud_model_v1", "1.0.0", "/path", 128, "{}", "admin", "desc");
        when(em.find(MlModel.class, 1L)).thenReturn(model);

        Optional<MlModel> opt = repository.findById(1L);
        assertThat(opt).isPresent();
        assertThat(opt.get().getModelName()).isEqualTo("fraud_model_v1");

        Optional<MlModel> nullOpt = repository.findById(null);
        assertThat(nullOpt).isEmpty();
    }

    @Test
    @DisplayName("findActiveByName executes parameterized query and returns active model")
    void testFindActiveByName() {
        MlModel activeModel = new MlModel("fraud_model_v1", "1.0.0", "/path", 128, "{}", "admin", "desc");
        activeModel.setActive(true);

        when(em.createQuery(anyString(), eq(MlModel.class))).thenReturn(typedQuery);
        when(typedQuery.setParameter("modelName", "fraud_model_v1")).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(activeModel));

        Optional<MlModel> opt = repository.findActiveByName("fraud_model_v1");
        assertThat(opt).isPresent();
        assertThat(opt.get().isActive()).isTrue();
        assertThat(opt.get().getVersion()).isEqualTo("1.0.0");

        verify(em).createQuery(
                "SELECT m FROM MlModel m WHERE m.modelName = :modelName AND m.active = true",
                MlModel.class
        );
    }

    @Test
    @DisplayName("findActiveByName returns empty Optional when modelName is blank or null")
    void testFindActiveByNameBlank() {
        assertThat(repository.findActiveByName(null)).isEmpty();
        assertThat(repository.findActiveByName("   ")).isEmpty();
        verifyNoInteractions(em);
    }

    @Test
    @DisplayName("findByModelNameAndVersion returns specific model version")
    void testFindByModelNameAndVersion() {
        MlModel model = new MlModel("fraud_model_v1", "2.1.0", "/path2", 150, "{}", "admin", "desc");

        when(em.createQuery(anyString(), eq(MlModel.class))).thenReturn(typedQuery);
        when(typedQuery.setParameter("modelName", "fraud_model_v1")).thenReturn(typedQuery);
        when(typedQuery.setParameter("version", "2.1.0")).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(model));

        Optional<MlModel> opt = repository.findByModelNameAndVersion("fraud_model_v1", "2.1.0");
        assertThat(opt).isPresent();
        assertThat(opt.get().getVersion()).isEqualTo("2.1.0");

        verify(em).createQuery(
                "SELECT m FROM MlModel m WHERE m.modelName = :modelName AND m.version = :version",
                MlModel.class
        );
    }

    @Test
    @DisplayName("findVersionHistory returns all model versions ordered by uploadedAt descending")
    void testFindVersionHistory() {
        MlModel v1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 100, "{}", "admin", "v1");
        MlModel v2 = new MlModel("fraud_model_v1", "2.0.0", "/path2", 120, "{}", "admin", "v2");

        when(em.createQuery(anyString(), eq(MlModel.class))).thenReturn(typedQuery);
        when(typedQuery.setParameter("modelName", "fraud_model_v1")).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(v2, v1));

        List<MlModel> history = repository.findVersionHistory("fraud_model_v1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getVersion()).isEqualTo("2.0.0");
        assertThat(history.get(1).getVersion()).isEqualTo("1.0.0");

        verify(em).createQuery(
                "SELECT m FROM MlModel m WHERE m.modelName = :modelName ORDER BY m.uploadedAt DESC",
                MlModel.class
        );
    }

    @Test
    @DisplayName("findAllActiveModels returns all currently active models across the registry")
    void testFindAllActiveModels() {
        MlModel m1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 100, "{}", "admin", "v1");
        m1.setActive(true);
        MlModel m2 = new MlModel("ast_reorder_policy", "1.0.0", "/path2", 200, "{}", "admin", "v2");
        m2.setActive(true);

        when(em.createQuery(anyString(), eq(MlModel.class))).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(m2, m1));

        List<MlModel> activeModels = repository.findAllActiveModels();
        assertThat(activeModels).hasSize(2);
        assertThat(activeModels).allMatch(MlModel::isActive);

        verify(em).createQuery(
                "SELECT m FROM MlModel m WHERE m.active = true ORDER BY m.modelName ASC",
                MlModel.class
        );
    }

    @Test
    @DisplayName("existsByModelNameAndVersion returns true when count is positive")
    void testExistsByModelNameAndVersion() {
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.setParameter("modelName", "fraud_model_v1")).thenReturn(countQuery);
        when(countQuery.setParameter("version", "1.0.0")).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);

        boolean exists = repository.existsByModelNameAndVersion("fraud_model_v1", "1.0.0");
        assertThat(exists).isTrue();

        when(countQuery.getSingleResult()).thenReturn(0L);
        boolean notExists = repository.existsByModelNameAndVersion("fraud_model_v1", "9.9.9");
        assertThat(notExists).isFalse();
    }

    @Test
    @DisplayName("deactivateAllVersions executes bulk update query")
    void testDeactivateAllVersions() {
        when(em.createQuery(anyString())).thenReturn(updateQuery);
        when(updateQuery.setParameter("modelName", "fraud_model_v1")).thenReturn(updateQuery);
        when(updateQuery.executeUpdate()).thenReturn(2);

        int updated = repository.deactivateAllVersions("fraud_model_v1");
        assertThat(updated).isEqualTo(2);

        verify(em).createQuery(
                "UPDATE MlModel m SET m.active = false WHERE m.modelName = :modelName"
        );
    }

    @Test
    @DisplayName("delete removes managed model entity")
    void testDelete() {
        MlModel model = new MlModel("fraud_model_v1", "1.0.0", "/path", 100, "{}", "admin", "desc");
        when(em.contains(model)).thenReturn(true);

        repository.delete(model);

        verify(em).remove(model);
    }
}
