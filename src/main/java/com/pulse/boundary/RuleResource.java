package com.pulse.boundary;

import com.pulse.boundary.filter.Secured;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;

/**
 * REST resource for rule compilation, execution, and analytical session tracking.
 * Secured with JWT Bearer token authentication.
 */
@Path("/rules")
@Secured
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuleResource {

    @GET
    @Path("/sessions")
    @RolesAllowed({"ENGINEER", "ADMIN"})
    public Response getSessions() {
        return Response.ok(Collections.emptyList()).build();
    }
}
