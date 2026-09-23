package com.pulse.control;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.api.ml.ModelRegistry;
import com.helix.api.ml.OnnxModelDescriptor;
import com.pulse.entity.MlModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise service managing machine-learning model artifacts, filesystem storage,
 * metadata persistence, version activation, and Redis Pub/Sub cluster broadcasting.
 *
 * <p>Implements the Helix engine {@link ModelRegistry} SPI, bridging PostgreSQL persistence
 * and local file storage with the in-process ONNX inference execution engine.</p>
 */
@ApplicationScoped
public class ModelRegistryService implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    private MlModelRepository modelRepository;

    @Inject
    private ModelActivationBroadcaster activationBroadcaster;

    @Inject
    @ConfigProperty(name = "helix.onnx.model.store.path", defaultValue = "/var/helix/models")
    private String modelStorePath;

    @Inject
    @ConfigProperty(name = "helix.onnx.model.max-size-bytes", defaultValue = "26214400") // 25 MB
    private long maxSizeBytes;

    public ModelRegistryService() {
    }

    public ModelRegistryService(MlModelRepository modelRepository,
                                ModelActivationBroadcaster activationBroadcaster,
                                String modelStorePath,
                                long maxSizeBytes) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository cannot be null");
        this.activationBroadcaster = activationBroadcaster;
        this.modelStorePath = (modelStorePath != null && !modelStorePath.isBlank()) ? modelStorePath : "/tmp/helix/models";
        this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : 26214400L;
    }

    /**
     * Uploads and registers a new ONNX model artifact.
     *
     * <p>Enforces version uniqueness, writes to filesystem storage atomically, validates
     * the ONNX binary graph structure using ONNX Runtime, and persists metadata in PostgreSQL.</p>
     *
     * @param modelName        unique model name
     * @param version          semantic version
     * @param onnxInputStream  input stream containing the .onnx binary
     * @param inputSchemaJson  JSON string defining feature names and input schema
     * @param uploadedBy       username of uploading engineer
     * @param description      model description
     * @return persisted {@link MlModel} entity
     */
    @Transactional
    public MlModel uploadModel(String modelName, String version, InputStream onnxInputStream,
                               String inputSchemaJson, String uploadedBy, String description) {
        validateUploadArguments(modelName, version, onnxInputStream, uploadedBy);

        String trimmedName = modelName.trim();
        String trimmedVersion = version.trim();

        if (modelRepository.existsByModelNameAndVersion(trimmedName, trimmedVersion)) {
            throw new IllegalArgumentException(
                    "Model '" + trimmedName + "' version '" + trimmedVersion + "' already exists in registry"
            );
        }

        Path storeDir = Path.of(modelStorePath, trimmedName, trimmedVersion);
        Path tempFile = null;

        try {
            Files.createDirectories(storeDir);
            tempFile = Files.createTempFile(storeDir, "model_upload_", ".tmp");

            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            int read;
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                while ((read = onnxInputStream.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > maxSizeBytes) {
                        throw new IllegalArgumentException(
                                "Uploaded model file exceeds maximum allowed size (" + maxSizeBytes + " bytes)"
                        );
                    }
                    os.write(buffer, 0, read);
                }
            }

            if (totalBytes == 0) {
                throw new IllegalArgumentException("Uploaded model file is empty");
            }

            // Validate ONNX graph structure and tensor signature
            validateOnnxBinary(tempFile);

            Path destination = storeDir.resolve("model.onnx");
            Files.move(tempFile, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tempFile = null;

            int fileSizeKb = Math.max(1, (int) (totalBytes / 1024));
            String schema = (inputSchemaJson != null && !inputSchemaJson.isBlank()) ? inputSchemaJson : "{}";

            MlModel model = new MlModel(
                    trimmedName,
                    trimmedVersion,
                    destination.toAbsolutePath().toString(),
                    fileSizeKb,
                    schema,
                    uploadedBy.trim(),
                    description
            );

            MlModel saved = modelRepository.save(model);
            log.info("Successfully uploaded and registered model '{}' version '{}' ({} KB) at {}",
                    trimmedName, trimmedVersion, fileSizeKb, destination);
            return saved;

        } catch (IllegalArgumentException e) {
            cleanupQuietly(tempFile);
            throw e;
        } catch (Exception e) {
            cleanupQuietly(tempFile);
            log.error("Failed to store and register model '{}' v{}: {}", trimmedName, trimmedVersion, e.getMessage(), e);
            throw new RuntimeException("Failed to register model: " + e.getMessage(), e);
        }
    }

    /**
     * Atomically activates a model version and broadcasts cluster invalidation.
     *
     * @param modelName unique model name
     * @param version   version to activate
     * @return activated {@link MlModel} entity
     */
    @Transactional
    public MlModel activateVersion(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("modelName and version cannot be null or blank");
        }

        String trimmedName = modelName.trim();
        String trimmedVersion = version.trim();

        MlModel model = modelRepository.findByModelNameAndVersion(trimmedName, trimmedVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Model '" + trimmedName + "' version '" + trimmedVersion + "' not found"
                ));

        // Atomically deactivate previous versions
        modelRepository.deactivateAllVersions(trimmedName);

        model.setActive(true);
        MlModel updated = modelRepository.save(model);

        if (activationBroadcaster != null) {
            activationBroadcaster.broadcastActivation(trimmedName, trimmedVersion, updated.getFilePath());
        }

        log.info("Model '{}' version '{}' is now active", trimmedName, trimmedVersion);
        return updated;
    }

    /**
     * Deletes a specific model version from persistence and deletes its physical storage.
     *
     * @param modelName unique model name
     * @param version   version to delete
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean deleteModelVersion(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            return false;
        }

        Optional<MlModel> opt = modelRepository.findByModelNameAndVersion(modelName.trim(), version.trim());
        if (opt.isEmpty()) {
            return false;
        }

        MlModel model = opt.get();
        modelRepository.delete(model);

        try {
            Path file = Path.of(model.getFilePath());
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            if (parent != null && Files.exists(parent) && isEmptyDirectory(parent)) {
                Files.deleteIfExists(parent);
            }
        } catch (Exception e) {
            log.warn("Could not delete physical model file '{}': {}", model.getFilePath(), e.getMessage());
        }

        log.info("Deleted model '{}' version '{}'", modelName, version);
        return true;
    }

    // ------------------------------------------------------------------------
    // ModelRegistry SPI Implementation
    // ------------------------------------------------------------------------

    @Override
    public Optional<OnnxModelDescriptor> resolveModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return modelRepository.findActiveByName(modelName.trim())
                .map(this::toDescriptor);
    }

    @Override
    public Optional<OnnxModelDescriptor> resolveModel(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        return modelRepository.findByModelNameAndVersion(modelName.trim(), version.trim())
                .map(this::toDescriptor);
    }

    @Override
    @Transactional
    public void registerModel(OnnxModelDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        if (!modelRepository.existsByModelNameAndVersion(descriptor.modelName(), descriptor.version())) {
            String schemaJson;
            try {
                schemaJson = objectMapper.writeValueAsString(Collections.singletonMap("features", descriptor.inputFeatures()));
            } catch (Exception e) {
                schemaJson = "{}";
            }
            int sizeKb = Math.max(1, (int) (descriptor.fileSizeBytes() / 1024));
            MlModel entity = new MlModel(
                    descriptor.modelName(),
                    descriptor.version(),
                    descriptor.modelPath(),
                    sizeKb,
                    schemaJson,
                    "system",
                    descriptor.description()
            );
            entity.setActive(descriptor.active());
            modelRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void activateModelVersion(String modelName, String version) {
        activateVersion(modelName, version);
    }

    @Override
    @Transactional
    public void invalidateModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            modelRepository.deactivateAllVersions(modelName.trim());
        }
    }

    @Override
    @Transactional
    public void invalidateModelVersion(String modelName, String version) {
        if (modelName != null && !modelName.isBlank() && version != null && !version.isBlank()) {
            modelRepository.findByModelNameAndVersion(modelName.trim(), version.trim())
                    .ifPresent(m -> {
                        m.setActive(false);
                        modelRepository.save(m);
                    });
        }
    }

    @Override
    public List<OnnxModelDescriptor> listModels() {
        return modelRepository.findAllActiveModels().stream()
                .map(this::toDescriptor)
                .toList();
    }

    @Override
    public List<OnnxModelDescriptor> listVersions(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Collections.emptyList();
        }
        return modelRepository.findVersionHistory(modelName.trim()).stream()
                .map(this::toDescriptor)
                .toList();
    }

    @Override
    public boolean hasModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return modelRepository.findActiveByName(modelName.trim()).isPresent();
    }

    @Override
    public boolean hasModelVersion(String modelName, String version) {
        if (modelName == null || modelName.isBlank() || version == null || version.isBlank()) {
            return false;
        }
        return modelRepository.existsByModelNameAndVersion(modelName.trim(), version.trim());
    }

    // ------------------------------------------------------------------------
    // Helper Methods
    // ------------------------------------------------------------------------

    private void validateOnnxBinary(Path path) {
        try (OrtSession session = OrtEnvironment.getEnvironment().createSession(path.toString())) {
            if (session.getInputNames().isEmpty()) {
                throw new IllegalArgumentException("Invalid ONNX model: model has no declared input tensors");
            }
            log.debug("Validated ONNX model at {}. Inputs: {}", path, session.getInputNames());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ONNX model file: " + e.getMessage(), e);
        }
    }

    private OnnxModelDescriptor toDescriptor(MlModel model) {
        List<String> features = parseInputFeatures(model.getInputSchema());
        long fileSizeBytes = model.getFileSizeKb() != null ? model.getFileSizeKb() * 1024L : 0L;

        return OnnxModelDescriptor.builder()
                .modelName(model.getModelName())
                .version(model.getVersion())
                .modelPath(model.getFilePath())
                .inputFeatures(features.isEmpty() ? List.of("feature_0") : features)
                .outputTensorName("probabilities")
                .outputIndex(1)
                .description(model.getDescription() != null ? model.getDescription() : "")
                .fileSizeBytes(fileSizeBytes)
                .active(model.isActive())
                .build();
    }

    private List<String> parseInputFeatures(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(inputSchemaJson);
            if (root.has("features") && root.get("features").isArray()) {
                List<String> features = new ArrayList<>();
                for (JsonNode f : root.get("features")) {
                    features.add(f.asText());
                }
                return features;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    private void validateUploadArguments(String modelName, String version, InputStream is, String uploadedBy) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName cannot be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version cannot be null or blank");
        }
        if (is == null) {
            throw new IllegalArgumentException("onnxInputStream cannot be null");
        }
        if (uploadedBy == null || uploadedBy.isBlank()) {
            throw new IllegalArgumentException("uploadedBy cannot be null or blank");
        }
    }

    private void cleanupQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isEmptyDirectory(Path dir) {
        try (var stream = Files.list(dir)) {
            return !stream.findAny().isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
