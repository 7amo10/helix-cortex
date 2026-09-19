package com.pulse.boundary;

import com.pulse.boundary.dto.CacheInvalidateRequest;
import com.pulse.boundary.dto.CacheInvalidateResponse;
import com.pulse.boundary.dto.CacheMetricsResponse;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.L4CacheService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST boundary exposing Redis L4 distributed cache telemetry and cluster-wide invalidation.
 * Secured via MicroProfile JWT authentication requiring ADMIN or OPERATOR role.
 */
@Path("/cache")
@Tag(name = "cache", description = "Distributed L4 Redis cache management and health telemetry")
@Secured
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CacheResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private L4CacheService cacheService;

    public CacheResource() {
    }

    public CacheResource(L4CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Retrieves health, latency, and hit/miss metrics for the L4 Redis distributed cache.
     *
     * @return CacheMetricsResponse with hit count, miss count, connected status, and fallback state
     */
    @GET
    @Path("/l4/metrics")
    @RolesAllowed({"ADMIN", "OPERATOR"})
    @Operation(summary = "Get L4 Redis cache metrics",
               description = "Returns hit count, miss count, hit ratio, connected status, and fallback state")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "L4 cache metrics returned successfully"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN or OPERATOR role")
    })
    public Response getMetrics() {
        if (cacheService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"L4CacheService not available\"}")
                    .build();
        }

        CacheMetricsResponse metrics = cacheService.getMetrics();
        return Response.ok(metrics).build();
    }

    /**
     * Broadcasts a cluster-wide cache invalidation event for the specified rule name.
     *
     * @param req invalidation request containing rule name and optional version
     * @return confirmation of broadcasted eviction
     */
    @POST
    @Path("/l4/invalidate")
    @RolesAllowed({"ADMIN", "OPERATOR"})
    @Operation(summary = "Invalidate rule in L4 cache",
               description = "Evicts rule bytecode from Redis and broadcasts eviction event across all cluster nodes")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Cluster invalidation broadcasted successfully"),
            @APIResponse(responseCode = "400", description = "Bad Request - ruleName is missing or invalid"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN or OPERATOR role")
    })
    public Response invalidate(CacheInvalidateRequest req) {
        if (req == null || req.ruleName() == null || req.ruleName().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"ruleName is required\"}")
                    .build();
        }

        if (cacheService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"L4CacheService not available\"}")
                    .build();
        }

        String ruleName = req.ruleName().trim();
        cacheService.invalidate(ruleName, req.version());

        CacheInvalidateResponse response = new CacheInvalidateResponse(
                "SUCCESS",
                "Cluster invalidation broadcasted for rule '" + ruleName + "'",
                ruleName
        );
        return Response.ok(response).build();
    }
}
