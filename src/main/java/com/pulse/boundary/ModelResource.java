package com.pulse.boundary;

import com.pulse.boundary.dto.ModelActionResponse;
import com.pulse.boundary.dto.ModelDetailResponse;
import com.pulse.boundary.dto.ModelSummaryResponse;
import com.pulse.boundary.dto.ModelUploadResponse;
import com.pulse.boundary.dto.ModelVersionDto;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.MlModelRepository;
import com.pulse.control.ModelRegistryService;
import com.pulse.entity.MlModel;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST boundary exposing endpoints for machine-learning model artifact upload,
 * querying, version activation, and deletion.
 *
 * <p>Protected via MicroProfile JWT authentication and role-based access control (RBAC).
 * Supports multipart/form-data upload of .onnx binary files with metadata validation.</p>
 */
@Path("/models")
@Tag(name = "models", description = "Machine-learning model registry and lifecycle management")
@Secured
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ModelResource {

    private static final Logger log = LoggerFactory.getLogger(ModelResource.class);
    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private ModelRegistryService registryService;

    @Inject
    private MlModelRepository modelRepository;

    @Inject
    @ConfigProperty(name = "helix.onnx.model.max-size-bytes", defaultValue = "26214400") // 25 MB
    private long maxSizeBytes;

    @Context
    private SecurityContext sc;

    @Context
    private HttpServletRequest httpRequest;

    public ModelResource() {
    }

    public ModelResource(ModelRegistryService registryService,
                         MlModelRepository modelRepository,
                         long maxSizeBytes) {
        this.registryService = registryService;
        this.modelRepository = modelRepository;
        this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : 26214400L;
    }

    /**
     * Lists all registered model families, their active versions, and version counts.
     */
    @GET
    @RolesAllowed({"ENGINEER", "OPERATOR", "DATA_SCIENTIST", "ADMIN"})
    @Operation(summary = "List all registered models", description = "Returns summary list of all models with active version tags")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of model summaries returned successfully"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid token")
    })
    public Response listModels() {
        List<MlModel> allModels = modelRepository.findAll();
        Map<String, List<MlModel>> grouped = new LinkedHashMap<>();
        for (MlModel m : allModels) {
            grouped.computeIfAbsent(m.getModelName(), k -> new ArrayList<>()).add(m);
        }

        List<ModelSummaryResponse> summaries = new ArrayList<>();
        for (Map.Entry<String, List<MlModel>> entry : grouped.entrySet()) {
            String name = entry.getKey();
            List<MlModel> versions = entry.getValue();
            MlModel active = versions.stream().filter(MlModel::isActive).findFirst().orElse(null);

            summaries.add(new ModelSummaryResponse(
                    name,
                    active != null ? active.getVersion() : "none",
                    versions.size(),
                    active != null ? active.getOutputType() : "FLOAT",
                    active != null ? active.getFileSizeKb() : 0
            ));
        }

        return Response.ok(summaries).build();
    }

    /**
     * Retrieves detailed metadata and complete version history for a specific model family.
     */
    @GET
    @Path("/{name}")
    @RolesAllowed({"ENGINEER", "OPERATOR", "DATA_SCIENTIST", "ADMIN"})
    @Operation(summary = "Get model details and version history", description = "Returns full version history and feature schema for named model")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Model detail returned successfully"),
            @APIResponse(responseCode = "400", description = "Missing or blank model name"),
            @APIResponse(responseCode = "404", description = "Model not found")
    })
    public Response getModelByName(@PathParam("name") String name) {
        if (name == null || name.isBlank()) {
            return badRequest("Model name is required");
        }

        List<MlModel> history = modelRepository.findVersionHistory(name.trim());
        if (history.isEmpty()) {
            return notFound("Model '" + name.trim() + "' not found");
        }

        String activeVersion = history.stream()
                .filter(MlModel::isActive)
                .map(MlModel::getVersion)
                .findFirst()
                .orElse("none");

        List<ModelVersionDto> versionDtos = history.stream()
                .map(m -> new ModelVersionDto(
                        m.getVersion(),
                        m.getFilePath(),
                        m.getFileSizeKb(),
                        m.getInputSchema(),
                        m.getOutputType(),
                        m.isActive(),
                        m.getUploadedBy(),
                        m.getUploadedAt().toString(),
                        m.getDescription()
                ))
                .toList();

        ModelDetailResponse response = new ModelDetailResponse(
                name.trim(),
                activeVersion,
                versionDtos.size(),
                versionDtos
        );

        return Response.ok(response).build();
    }

    /**
     * Multipart endpoint for uploading a new ONNX model artifact and JSON metadata.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({"ADMIN", "DATA_SCIENTIST"})
    @Operation(summary = "Upload and register ONNX model", description = "Uploads a .onnx binary and JSON input schema metadata")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Model uploaded and registered successfully"),
            @APIResponse(responseCode = "400", description = "Invalid model file, duplicate version, or missing metadata"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN or DATA_SCIENTIST role")
    })
    public Response uploadMultipart(List<EntityPart> parts, @Context SecurityContext context) {
        SecurityContext effectiveSc = (context != null) ? context : this.sc;

        InputStream fileStream = null;
        String filename = null;
        String modelName = null;
        String version = null;
        String inputSchema = "{}";
        String description = null;

        if (parts != null && !parts.isEmpty()) {
            for (EntityPart part : parts) {
                String partName = part.getName();
                if ("file".equalsIgnoreCase(partName)) {
                    fileStream = part.getContent();
                    filename = part.getFileName().orElse("model.onnx");
                } else if ("name".equalsIgnoreCase(partName) || "modelName".equalsIgnoreCase(partName)) {
                    modelName = readPartString(part);
                } else if ("version".equalsIgnoreCase(partName)) {
                    version = readPartString(part);
                } else if ("schema".equalsIgnoreCase(partName) || "inputSchema".equalsIgnoreCase(partName)) {
                    inputSchema = readPartString(part);
                } else if ("description".equalsIgnoreCase(partName)) {
                    description = readPartString(part);
                }
            }
        } else if (httpRequest != null) {
            try {
                Part filePart = httpRequest.getPart("file");
                if (filePart != null) {
                    fileStream = filePart.getInputStream();
                    filename = filePart.getSubmittedFileName() != null ? filePart.getSubmittedFileName() : "model.onnx";
                }
                Part namePart = httpRequest.getPart("name");
                if (namePart != null) {
                    modelName = new String(namePart.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                Part versionPart = httpRequest.getPart("version");
                if (versionPart != null) {
                    version = new String(versionPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                Part schemaPart = httpRequest.getPart("schema");
                if (schemaPart != null) {
                    inputSchema = new String(schemaPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                Part descPart = httpRequest.getPart("description");
                if (descPart != null) {
                    description = new String(descPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {
            }
        }

        return upload(fileStream, filename, modelName, version, inputSchema, description, effectiveSc);
    }

    /**
     * Programmatic upload entry point, also utilized for testing and direct API invocation.
     */
    public Response upload(InputStream fileStream, String filename, String modelName,
                           String version, String inputSchema, String description,
                           SecurityContext context) {
        if (filename == null || !filename.toLowerCase().endsWith(".onnx")) {
            return badRequest("Model file must have .onnx extension (received: " + filename + ")");
        }

        if (fileStream == null) {
            return badRequest("Model file part is missing or empty");
        }

        if (modelName == null || modelName.isBlank()) {
            return badRequest("Model name is required");
        }

        if (version == null || version.isBlank()) {
            return badRequest("Model version is required");
        }

        // Buffer stream to check size threshold before processing
        byte[] fileBytes;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = fileStream.read(buffer)) != -1) {
                total += read;
                if (total > maxSizeBytes) {
                    return badRequest("Uploaded file size exceeds maximum allowed limit (" + maxSizeBytes + " bytes)");
                }
                baos.write(buffer, 0, read);
            }
            fileBytes = baos.toByteArray();
        } catch (Exception e) {
            return badRequest("Failed to read uploaded file stream: " + e.getMessage());
        }

        if (fileBytes.length == 0) {
            return badRequest("Model file cannot be empty");
        }

        String uploadedBy = (context != null && context.getUserPrincipal() != null)
                ? context.getUserPrincipal().getName()
                : "anonymous";

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
            MlModel saved = registryService.uploadModel(
                    modelName.trim(),
                    version.trim(),
                    bais,
                    inputSchema,
                    uploadedBy,
                    description
            );

            URI location = URI.create("/api/v1/models/" + saved.getModelName());
            ModelUploadResponse response = new ModelUploadResponse(
                    saved.getModelName(),
                    saved.getVersion(),
                    saved.getFilePath(),
                    saved.getFileSizeKb(),
                    saved.isActive(),
                    location.toString()
            );

            return Response.created(location).entity(response).build();

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to upload model '{}' v{}: {}", modelName, version, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":500,\"title\":\"Internal Server Error\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        }
    }

    /**
     * Activates a specific version of a model, hot-swapping it cluster-wide.
     */
    @PUT
    @Path("/{name}/activate")
    @RolesAllowed({"ADMIN"})
    @Operation(summary = "Set active model version", description = "Activates a model version and broadcasts cluster hot-swap via Redis")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Model version activated successfully"),
            @APIResponse(responseCode = "400", description = "Missing version parameter"),
            @APIResponse(responseCode = "404", description = "Model or version not found"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response activateVersion(@PathParam("name") String name,
                                    @QueryParam("version") String version,
                                    @Context SecurityContext context) {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            return badRequest("Model name and version are required");
        }

        try {
            MlModel activated = registryService.activateVersion(name.trim(), version.trim());
            return Response.ok(new ModelActionResponse(
                    "SUCCESS",
                    activated.getModelName(),
                    activated.getVersion(),
                    "Model version activated successfully"
            )).build();
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Deletes a specific version of a model from the registry and filesystem.
     */
    @DELETE
    @Path("/{name}/versions/{version}")
    @RolesAllowed({"ADMIN"})
    @Operation(summary = "Delete specific model version", description = "Deletes model version metadata and binary artifact")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Model version deleted successfully"),
            @APIResponse(responseCode = "400", description = "Missing model name or version"),
            @APIResponse(responseCode = "404", description = "Model version not found"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response deleteVersion(@PathParam("name") String name,
                                  @PathParam("version") String version,
                                  @Context SecurityContext context) {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            return badRequest("Model name and version are required");
        }

        boolean deleted = registryService.deleteModelVersion(name.trim(), version.trim());
        if (!deleted) {
            return notFound("Model '" + name.trim() + "' version '" + version.trim() + "' not found");
        }

        return Response.ok(new ModelActionResponse(
                "SUCCESS",
                name.trim(),
                version.trim(),
                "Model version deleted successfully"
        )).build();
    }

    // ------------------------------------------------------------------------
    // Helper Methods
    // ------------------------------------------------------------------------

    private Response badRequest(String detail) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(PROBLEM_JSON)
                .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"" + escapeJson(detail) + "\"}")
                .build();
    }

    private Response notFound(String detail) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(PROBLEM_JSON)
                .entity("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"" + escapeJson(detail) + "\"}")
                .build();
    }

    private String readPartString(EntityPart part) {
        try {
            return new String(part.getContent().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
