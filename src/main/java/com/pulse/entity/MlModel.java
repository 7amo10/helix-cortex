package com.pulse.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity representing a machine-learning model registered in Helix Cortex.
 *
 * <p>Stores model metadata, version history, storage file paths, and JSONB input feature schemas.
 * Enforces a unique constraint on ({@code model_name}, {@code version}) and provides L2 caching
 * for high-throughput rule compilation and runtime inference lookup.</p>
 */
@Entity
@Table(name = "helix_ml_models",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_helix_ml_models_name_version", columnNames = {"model_name", "version"})
        },
        indexes = {
                @Index(name = "idx_helix_ml_models_name", columnList = "model_name"),
                @Index(name = "idx_helix_ml_models_active", columnList = "model_name, is_active")
        }
)
@Cacheable(true)
public class MlModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "model_name", length = 255, nullable = false)
    private String modelName;

    @Column(name = "version", length = 64, nullable = false)
    private String version;

    @Column(name = "file_path", columnDefinition = "TEXT", nullable = false)
    private String filePath;

    @Column(name = "file_size_kb", nullable = false)
    private Integer fileSizeKb;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", columnDefinition = "jsonb", nullable = false)
    private String inputSchema;

    @Column(name = "output_type", length = 32, nullable = false)
    private String outputType = "FLOAT";

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    @Column(name = "uploaded_by", length = 255, nullable = false)
    private String uploadedBy;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public MlModel() {
        this.uploadedAt = Instant.now();
        this.outputType = "FLOAT";
        this.active = false;
    }

    public MlModel(String modelName, String version, String filePath, Integer fileSizeKb,
                   String inputSchema, String uploadedBy, String description) {
        this();
        this.modelName = modelName;
        this.version = version;
        this.filePath = filePath;
        this.fileSizeKb = fileSizeKb;
        this.inputSchema = inputSchema;
        this.uploadedBy = uploadedBy;
        this.description = description;
    }

    public MlModel(String modelName, String version, String filePath, Integer fileSizeKb,
                   String inputSchema, String outputType, boolean active,
                   String uploadedBy, String description) {
        this(modelName, version, filePath, fileSizeKb, inputSchema, uploadedBy, description);
        this.outputType = (outputType != null && !outputType.isBlank()) ? outputType : "FLOAT";
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getFileSizeKb() {
        return fileSizeKb;
    }

    public void setFileSizeKb(Integer fileSizeKb) {
        this.fileSizeKb = fileSizeKb;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MlModel mlModel = (MlModel) o;
        return Objects.equals(modelName, mlModel.modelName) &&
                Objects.equals(version, mlModel.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelName, version);
    }

    @Override
    public String toString() {
        return "MlModel{" +
                "id=" + id +
                ", modelName='" + modelName + '\'' +
                ", version='" + version + '\'' +
                ", filePath='" + filePath + '\'' +
                ", fileSizeKb=" + fileSizeKb +
                ", outputType='" + outputType + '\'' +
                ", active=" + active +
                ", uploadedBy='" + uploadedBy + '\'' +
                ", uploadedAt=" + uploadedAt +
                '}';
    }
}
