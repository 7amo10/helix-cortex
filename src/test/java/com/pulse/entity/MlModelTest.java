package com.pulse.entity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MlModelTest {

    @Test
    @DisplayName("MlModel entity annotations: @Entity, @Table(name='helix_ml_models'), uniqueConstraint, indexes, @Cacheable(true)")
    void testMlModelAnnotations() {
        Entity entity = MlModel.class.getAnnotation(Entity.class);
        assertThat(entity).isNotNull();

        Table table = MlModel.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("helix_ml_models");

        // Unique constraint on (model_name, version)
        UniqueConstraint[] uniqueConstraints = table.uniqueConstraints();
        assertThat(uniqueConstraints).hasSize(1);
        assertThat(uniqueConstraints[0].name()).isEqualTo("uk_helix_ml_models_name_version");
        assertThat(uniqueConstraints[0].columnNames()).containsExactly("model_name", "version");

        // Indexes on model_name and (model_name, is_active)
        Index[] indexes = table.indexes();
        assertThat(indexes).hasSize(2);
        assertThat(indexes).extracting(Index::name).containsExactlyInAnyOrder(
                "idx_helix_ml_models_name",
                "idx_helix_ml_models_active"
        );

        Cacheable cacheable = MlModel.class.getAnnotation(Cacheable.class);
        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).isTrue();
    }

    @Test
    @DisplayName("MlModel fields validation: id, modelName, version, filePath, fileSizeKb, inputSchema, outputType, active, uploadedBy, uploadedAt, description")
    void testMlModelFields() throws NoSuchFieldException {
        Field idField = MlModel.class.getDeclaredField("id");
        assertThat(idField.getAnnotation(Id.class)).isNotNull();
        assertThat(idField.getAnnotation(GeneratedValue.class).strategy()).isEqualTo(GenerationType.IDENTITY);
        Column idCol = idField.getAnnotation(Column.class);
        assertThat(idCol).isNotNull();
        assertThat(idCol.name()).isEqualTo("id");

        Field modelNameField = MlModel.class.getDeclaredField("modelName");
        Column mnCol = modelNameField.getAnnotation(Column.class);
        assertThat(mnCol).isNotNull();
        assertThat(mnCol.name()).isEqualTo("model_name");
        assertThat(mnCol.length()).isEqualTo(255);
        assertThat(mnCol.nullable()).isFalse();

        Field versionField = MlModel.class.getDeclaredField("version");
        Column vCol = versionField.getAnnotation(Column.class);
        assertThat(vCol).isNotNull();
        assertThat(vCol.name()).isEqualTo("version");
        assertThat(vCol.length()).isEqualTo(64);
        assertThat(vCol.nullable()).isFalse();

        Field filePathField = MlModel.class.getDeclaredField("filePath");
        Column fpCol = filePathField.getAnnotation(Column.class);
        assertThat(fpCol).isNotNull();
        assertThat(fpCol.name()).isEqualTo("file_path");
        assertThat(fpCol.columnDefinition()).isEqualTo("TEXT");
        assertThat(fpCol.nullable()).isFalse();

        Field fileSizeKbField = MlModel.class.getDeclaredField("fileSizeKb");
        Column fsCol = fileSizeKbField.getAnnotation(Column.class);
        assertThat(fsCol).isNotNull();
        assertThat(fsCol.name()).isEqualTo("file_size_kb");
        assertThat(fsCol.nullable()).isFalse();

        Field inputSchemaField = MlModel.class.getDeclaredField("inputSchema");
        Column isCol = inputSchemaField.getAnnotation(Column.class);
        assertThat(isCol).isNotNull();
        assertThat(isCol.name()).isEqualTo("input_schema");
        assertThat(isCol.columnDefinition()).isEqualTo("jsonb");
        assertThat(isCol.nullable()).isFalse();
        JdbcTypeCode jdbcTypeCode = inputSchemaField.getAnnotation(JdbcTypeCode.class);
        assertThat(jdbcTypeCode).isNotNull();
        assertThat(jdbcTypeCode.value()).isEqualTo(SqlTypes.JSON);

        Field outputTypeField = MlModel.class.getDeclaredField("outputType");
        Column otCol = outputTypeField.getAnnotation(Column.class);
        assertThat(otCol).isNotNull();
        assertThat(otCol.name()).isEqualTo("output_type");
        assertThat(otCol.length()).isEqualTo(32);
        assertThat(otCol.nullable()).isFalse();

        Field activeField = MlModel.class.getDeclaredField("active");
        Column actCol = activeField.getAnnotation(Column.class);
        assertThat(actCol).isNotNull();
        assertThat(actCol.name()).isEqualTo("is_active");
        assertThat(actCol.nullable()).isFalse();

        Field uploadedByField = MlModel.class.getDeclaredField("uploadedBy");
        Column ubCol = uploadedByField.getAnnotation(Column.class);
        assertThat(ubCol).isNotNull();
        assertThat(ubCol.name()).isEqualTo("uploaded_by");
        assertThat(ubCol.length()).isEqualTo(255);
        assertThat(ubCol.nullable()).isFalse();

        Field uploadedAtField = MlModel.class.getDeclaredField("uploadedAt");
        Column uaCol = uploadedAtField.getAnnotation(Column.class);
        assertThat(uaCol).isNotNull();
        assertThat(uaCol.name()).isEqualTo("uploaded_at");
        assertThat(uaCol.nullable()).isFalse();

        Field descField = MlModel.class.getDeclaredField("description");
        Column descCol = descField.getAnnotation(Column.class);
        assertThat(descCol).isNotNull();
        assertThat(descCol.name()).isEqualTo("description");
        assertThat(descCol.columnDefinition()).isEqualTo("TEXT");
    }

    @Test
    @DisplayName("MlModel default constructor initializes uploadedAt, default outputType 'FLOAT', and active=false")
    void testDefaultConstructor() {
        MlModel model = new MlModel();
        assertThat(model.getUploadedAt()).isNotNull();
        assertThat(model.getOutputType()).isEqualTo("FLOAT");
        assertThat(model.isActive()).isFalse();
    }

    @Test
    @DisplayName("MlModel parameterized constructor initializes all core attributes correctly")
    void testParameterizedConstructor() {
        String schema = "{\"features\": [\"amount\", \"velocity_1h\"]}";
        MlModel model = new MlModel(
                "fraud_model_v1",
                "1.0.0",
                "/opt/models/fraud_v1.onnx",
                256,
                schema,
                "data_scientist_1",
                "Production fraud scoring model"
        );

        assertThat(model.getModelName()).isEqualTo("fraud_model_v1");
        assertThat(model.getVersion()).isEqualTo("1.0.0");
        assertThat(model.getFilePath()).isEqualTo("/opt/models/fraud_v1.onnx");
        assertThat(model.getFileSizeKb()).isEqualTo(256);
        assertThat(model.getInputSchema()).isEqualTo(schema);
        assertThat(model.getUploadedBy()).isEqualTo("data_scientist_1");
        assertThat(model.getDescription()).isEqualTo("Production fraud scoring model");
        assertThat(model.getOutputType()).isEqualTo("FLOAT");
        assertThat(model.isActive()).isFalse();
        assertThat(model.getUploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("MlModel equality and hashCode are based on natural business key (modelName, version)")
    void testEqualsAndHashCode() {
        MlModel model1 = new MlModel("fraud_model_v1", "1.0.0", "/path1", 100, "{}", "user1", "desc1");
        MlModel model2 = new MlModel("fraud_model_v1", "1.0.0", "/path2", 200, "{\"a\":1}", "user2", "desc2");
        MlModel model3 = new MlModel("fraud_model_v1", "2.0.0", "/path1", 100, "{}", "user1", "desc1");

        assertThat(model1).isEqualTo(model2);
        assertThat(model1.hashCode()).isEqualTo(model2.hashCode());
        assertThat(model1).isNotEqualTo(model3);
    }
}
