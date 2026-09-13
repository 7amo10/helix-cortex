package com.pulse.boundary;

import com.helix.api.RuleCompilationException;
import com.pulse.boundary.dto.ExecutionRequest;
import com.pulse.boundary.dto.RuleRequest;
import com.pulse.boundary.filter.Secured;
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

import java.util.Collections;
import java.util.List;

/**
 * REST boundary for rule compilation, execution, and analytical session tracking.
 * Secured with JWT Bearer token authentication and role-based access control.
 */
@Path("/rules")
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

    @Context
    private SecurityContext securityContext;

    public RuleResource() {
    }

    public RuleResource(RuleSessionControl control, RuleSessionRepository repo, SecurityContext securityContext) {
        this.control = control;
        this.repo = repo;
        this.securityContext = securityContext;
    }

    @POST
    @Path("/compile")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    @Transactional
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

    @GET
    @Path("/sessions")
    @RolesAllowed("ADMIN")
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
    public Response getMySessions() {
        String username = securityContext != null && securityContext.getUserPrincipal() != null ?
                securityContext.getUserPrincipal().getName() : "";
        List<RuleSession> sessions = repo != null ? repo.findByEngineerId(username) : Collections.emptyList();
        return Response.ok(sessions).build();
    }

    @GET
    @Path("/sessions/high-density")
    @RolesAllowed("ADMIN")
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
}
