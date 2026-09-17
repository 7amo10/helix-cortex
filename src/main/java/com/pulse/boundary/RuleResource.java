package com.pulse.boundary;

import com.helix.api.RuleCompilationException;
import com.pulse.boundary.dto.ExecutionRequest;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.RuleExecutionService;
import com.pulse.control.RuleSessionControl;
import com.pulse.control.RuleSessionRepository;
import com.pulse.entity.OpcodeMetric;
import com.pulse.entity.RuleSession;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST boundary for rule compilation, execution, and analytical session tracking.
 * Secured with JWT Bearer token authentication and role-based access control.
 */
@Path("/rules")
@Tag(name = "rules", description = "Dynamic bytecode rule compilation and evaluation")
@Secured
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuleResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private RuleSessionControl control;

    @Inject
    private RuleSessionRepository repo;

    @Inject
    private RuleExecutionService executionService;

    @Context
    private SecurityContext securityContext;

    public RuleResource() {
    }

    public RuleResource(RuleSessionControl control, RuleSessionRepository repo, SecurityContext securityContext) {
        this(control, repo, securityContext, null);
    }

    public RuleResource(RuleSessionControl control, RuleSessionRepository repo, SecurityContext securityContext, RuleExecutionService executionService) {
        this.control = control;
        this.repo = repo;
        this.securityContext = securityContext;
        this.executionService = executionService;
    }

    @POST
    @Path("/compile")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Transactional
    @Operation(summary = "Compile dynamic rule to bytecode", description = "Compiles raw rule code into JVM bytecode and stores a new RuleSession")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Rule compiled successfully"),
            @APIResponse(responseCode = "400", description = "Compilation error or malformed rule expression")
    })
    public Response compileRule(RuleRequest req) throws RuleCompilationException {
        if (req == null || req.ruleName() == null || req.expression() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"ruleName and expression are required\"}")
                    .build();
        }

        String username = securityContext != null && securityContext.getUserPrincipal() != null ?
                securityContext.getUserPrincipal().getName() : "anonymous";

        RuleSession session = control.compileAndSave(req, username);
        return Response.status(Response.Status.CREATED)
                .entity(session)
                .build();
    }

    @POST
    @Path("/execute/{sessionId}")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Transactional
    @Operation(summary = "Execute compiled rule", description = "Evaluates a previously compiled rule against supplied inputs and records opcode metrics")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rule evaluated successfully with execution time and results"),
            @APIResponse(responseCode = "400", description = "Invalid session ID or malformed input payload"),
            @APIResponse(responseCode = "404", description = "Rule session not found")
    })
    public Response executeRule(@PathParam("sessionId") Long sessionId, ExecutionRequest req) {
        if (sessionId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"sessionId is required\"}")
                    .build();
        }

        if (repo != null && repo.findById(sessionId).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(PROBLEM_JSON)
                    .entity(String.format("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"Session not found with id: %d\"}", sessionId))
                    .build();
        }

        var variables = req != null && req.variables() != null ? req.variables() : Collections.<String, Object>emptyMap();
        OpcodeMetric metric = control.executeAndSave(sessionId, variables);
        return Response.ok(metric).build();
    }

    @POST
    @Path("/execute")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Transactional
    @Operation(summary = "Execute rule via virtual threads", description = "Evaluates rule on Project Loom virtual threads with security context propagation")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rule evaluated successfully with virtual threads"),
            @APIResponse(responseCode = "400", description = "Missing sessionId or malformed input payload"),
            @APIResponse(responseCode = "404", description = "Rule session not found")
    })
    public Response executeDirect(ExecutionRequest req, @QueryParam("sessionId") Long querySessionId) {
        Long targetSessionId = (req != null && req.sessionId() != null) ? req.sessionId() : querySessionId;
        if (targetSessionId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"sessionId is required in payload or query parameter\"}")
                    .build();
        }
        return executeRule(targetSessionId, req);
    }

    @POST
    @Path("/execute/batch")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Transactional
    @Operation(summary = "Batch evaluate rules via virtual threads", description = "Evaluates batch inputs concurrently across Project Loom virtual threads")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Batch executed successfully with aggregated opcode metrics"),
            @APIResponse(responseCode = "400", description = "Malformed batch execution request"),
            @APIResponse(responseCode = "404", description = "Rule session not found")
    })
    public Response executeBatch(com.pulse.boundary.dto.BatchExecutionRequest req) {
        if (req == null || req.sessionId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"sessionId is required\"}")
                    .build();
        }

        if (repo != null && repo.findById(req.sessionId()).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(PROBLEM_JSON)
                    .entity(String.format("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"Session not found with id: %d\"}", req.sessionId()))
                    .build();
        }

        if (executionService != null) {
            var response = executionService.executeBatch(req, securityContext);
            return Response.ok(response).build();
        }

        var variablesList = req.batch() != null ? req.batch() : Collections.<Map<String, Object>>emptyList();
        List<OpcodeMetric> metrics = new java.util.ArrayList<>();
        long start = System.nanoTime();
        for (var vars : variablesList) {
            metrics.add(control.executeAndSave(req.sessionId(), vars));
        }
        long totalTime = System.nanoTime() - start;
        return Response.ok(new com.pulse.boundary.dto.BatchExecutionResponse(req.sessionId(), metrics.size(), totalTime, metrics)).build();
    }

    @GET
    @Path("/sessions")
    @RolesAllowed("ADMIN")
    @Operation(summary = "List all rule sessions (ADMIN)", description = "Retrieves all rule execution sessions across all engineers")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of rule sessions"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response getSessions() {
        if (securityContext != null && securityContext.getUserPrincipal() != null && !securityContext.isUserInRole("ADMIN")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Only ADMIN role is permitted to view all sessions\"}")
                    .build();
        }
        List<RuleSession> sessions = repo != null ? repo.findAll() : Collections.emptyList();
        return Response.ok(sessions).build();
    }

    @GET
    @Path("/sessions/mine")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Operation(summary = "List caller rule sessions", description = "Retrieves rule sessions submitted by the authenticated engineer")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of caller rule sessions")
    })
    public Response getMySessions() {
        String username = securityContext != null && securityContext.getUserPrincipal() != null ?
                securityContext.getUserPrincipal().getName() : "";
        List<RuleSession> sessions = repo != null ? repo.findByEngineerId(username) : Collections.emptyList();
        return Response.ok(sessions).build();
    }

    @GET
    @Path("/sessions/{sessionId}")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Operation(summary = "Get rule session by ID", description = "Retrieves a single rule session by its identifier")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rule session details"),
            @APIResponse(responseCode = "404", description = "Session not found")
    })
    public Response getSessionById(@PathParam("sessionId") Long sessionId) {
        if (sessionId == null) {
            return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON).build();
        }
        Optional<RuleSession> session = repo != null ? repo.findById(sessionId) : Optional.empty();
        if (session.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(PROBLEM_JSON)
                    .entity(String.format("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"Session not found with id: %d\"}", sessionId))
                    .build();
        }
        return Response.ok(session.get()).build();
    }

    @GET
    @Path("/sessions/high-density")
    @RolesAllowed("ADMIN")
    @Operation(summary = "Query high opcode density sessions (ADMIN)", description = "Queries sessions where total opcode count exceeds the threshold")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of high density sessions"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response getHighDensitySessions(@QueryParam("threshold") @DefaultValue("0") long threshold) {
        if (securityContext != null && securityContext.getUserPrincipal() != null && !securityContext.isUserInRole("ADMIN")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Only ADMIN role is permitted to view high density sessions\"}")
                    .build();
        }
        List<RuleSession> sessions = repo != null ? repo.findHighOpcodeDensitySessions(threshold) : Collections.emptyList();
        return Response.ok(sessions).build();
    }

    public void setControl(RuleSessionControl control) {
        this.control = control;
    }

    public void setRepo(RuleSessionRepository repo) {
        this.repo = repo;
    }

    public void setSecurityContext(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    public void setExecutionService(RuleExecutionService executionService) {
        this.executionService = executionService;
    }
}
