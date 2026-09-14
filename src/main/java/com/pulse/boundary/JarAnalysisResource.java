package com.pulse.boundary;

import com.pulse.boundary.filter.Secured;
import com.pulse.control.JarAnalysisControl;
import com.pulse.entity.JarAnalysis;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * JAX-RS boundary exposing JAR archive upload for async bytecode analysis
 * and querying analysis job sessions.
 */
@Path("/jars")
@Tag(name = "jars", description = "Asynchronous JAR archive bytecode disassembly and antipattern analysis")
@Secured
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class JarAnalysisResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private JarAnalysisControl control;

    @Context
    private SecurityContext sc;

    @Context
    private HttpServletRequest request;

    public JarAnalysisResource() {
    }

    public JarAnalysisResource(JarAnalysisControl control) {
        this.control = control;
    }

    /**
     * Accepts multipart/form-data upload of a JAR archive, offloading bytecode analysis
     * asynchronously to ManagedExecutorService and returning 202 Accepted.
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Operation(summary = "Upload and asynchronously analyze JAR bytecode", description = "Submits a JAR archive for asynchronous opcode counting and antipattern detection, returning 202 with Location header")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "JAR accepted for asynchronous analysis"),
            @APIResponse(responseCode = "400", description = "Missing file part or invalid archive format")
    })
    public Response analyzeMultipart(List<EntityPart> parts, @Context SecurityContext context) {
        SecurityContext effectiveSc = (context != null) ? context : this.sc;
        InputStream jarStream = null;
        String filename = "unknown.jar";

        if (parts != null && !parts.isEmpty()) {
            for (EntityPart part : parts) {
                if ("file".equalsIgnoreCase(part.getName())) {
                    jarStream = part.getContent();
                    filename = part.getFileName().orElse("uploaded.jar");
                    break;
                }
            }
            if (jarStream == null) {
                EntityPart first = parts.get(0);
                jarStream = first.getContent();
                filename = first.getFileName().orElse("uploaded.jar");
            }
        } else if (request != null) {
            try {
                Part part = request.getPart("file");
                if (part != null) {
                    jarStream = part.getInputStream();
                    filename = part.getSubmittedFileName() != null ? part.getSubmittedFileName() : "uploaded.jar";
                }
            } catch (Exception ignored) {
                // Fall back to missing stream handling
            }
        }

        if (jarStream == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Missing file in multipart form data\"}")
                    .build();
        }

        return submitAnalysis(jarStream, filename, effectiveSc);
    }

    /**
     * Direct programmatic entry point for JAR analysis submission, accessible by tests.
     */
    public Response analyze(InputStream jarStream, String filename, SecurityContext context) {
        SecurityContext effectiveSc = (context != null) ? context : this.sc;
        return submitAnalysis(jarStream, filename, effectiveSc);
    }

    private Response submitAnalysis(InputStream jarStream, String filename, SecurityContext context) {
        if (jarStream == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"JAR input stream cannot be null\"}")
                    .build();
        }

        String engineerId = (context != null && context.getUserPrincipal() != null)
                ? context.getUserPrincipal().getName()
                : "anonymous";

        try {
            Long analysisId = control.submitAsync(jarStream, filename, engineerId);
            URI location = URI.create("/api/v1/jars/sessions/" + analysisId);
            return Response.accepted()
                    .location(location)
                    .entity("{\"id\":" + analysisId + ",\"status\":\"PENDING\",\"location\":\"" + location + "\"}")
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":500,\"title\":\"Internal Server Error\",\"detail\":\"Failed to read uploaded JAR file\"}")
                    .build();
        }
    }

    /**
     * Retrieves status and metrics for a specific JAR analysis session.
     */
    @GET
    @Path("/sessions/{id}")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Operation(summary = "Get JAR analysis details by ID", description = "Retrieves class-level opcode metrics and detected antipatterns for an analysis job")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Analysis details and class metrics"),
            @APIResponse(responseCode = "400", description = "Missing or invalid session ID"),
            @APIResponse(responseCode = "404", description = "Analysis job not found")
    })
    public Response getSessionById(@PathParam("id") Long id) {
        if (id == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Session ID is required\"}")
                    .build();
        }

        Optional<JarAnalysis> sessionOpt = control.findById(id);
        if (sessionOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"JAR analysis session not found for id " + id + "\"}")
                    .build();
        }

        return Response.ok(sessionOpt.get()).build();
    }

    /**
     * Lists all JAR analysis sessions (ADMIN only).
     */
    @GET
    @Path("/sessions")
    @RolesAllowed("ADMIN")
    @Operation(summary = "List all JAR analysis jobs (ADMIN)", description = "Retrieves all submitted JAR analysis jobs with completion statuses")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of JAR analysis jobs"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response getAllSessions() {
        List<JarAnalysis> sessions = control.findAll();
        return Response.ok(sessions).build();
    }

    public void setControl(JarAnalysisControl control) {
        this.control = control;
    }

    public void setSecurityContext(SecurityContext sc) {
        this.sc = sc;
    }

    public void setHttpServletRequest(HttpServletRequest request) {
        this.request = request;
    }
}
