package com.pulse.boundary;

import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.TelemetryControl;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * JAX-RS resource exposing live JVM telemetry via Server-Sent Events (SSE)
 * and point-in-time snapshot inspection for administration.
 */
@Path("/telemetry")
@Secured
@RequestScoped
public class TelemetryResource {

    @Inject
    private TelemetryControl telemetryControl;

    public TelemetryResource() {
    }

    public TelemetryResource(TelemetryControl telemetryControl) {
        this.telemetryControl = telemetryControl;
    }

    /**
     * Server-Sent Events endpoint streaming real-time JVM metrics at 1-second intervals.
     * Accessible only by administrators.
     *
     * @param sink client SSE event sink
     * @param sse  JAX-RS SSE context
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed("ADMIN")
    public void stream(@Context SseEventSink sink, @Context Sse sse) {
        if (sink != null && telemetryControl != null) {
            telemetryControl.registerSink(sink);
        }
    }

    /**
     * Point-in-time snapshot of JVM memory, garbage collection, and thread counts.
     *
     * @return 200 OK with JvmTelemetrySnapshot
     */
    @GET
    @Path("/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response getSnapshot() {
        if (telemetryControl == null) {
            return Response.serverError().build();
        }
        JvmTelemetrySnapshot snapshot = telemetryControl.currentSnapshot();
        return Response.ok(snapshot).build();
    }

    public void setTelemetryControl(TelemetryControl telemetryControl) {
        this.telemetryControl = telemetryControl;
    }
}
