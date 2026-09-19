package com.pulse.boundary;

import com.helix.profiler.flamegraph.MetricType;
import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import com.pulse.boundary.filter.Secured;
import com.pulse.boundary.dto.StreamThroughputSnapshot;
import com.pulse.control.FlameGraphControl;
import com.pulse.control.StreamThroughputControl;
import com.pulse.control.TelemetryControl;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * JAX-RS resource exposing live JVM telemetry via Server-Sent Events (SSE),
 * point-in-time snapshot inspection, folded stack trace profiling for flame graphs,
 * and real-time streaming throughput telemetry.
 */
@Path("/telemetry")
@Tag(name = "telemetry", description = "HotSpot JVM runtime telemetry snapshots, folded stack profiling, and SSE streaming")
@Secured
@RequestScoped
public class TelemetryResource {

    @Inject
    private TelemetryControl telemetryControl;

    @Inject
    private FlameGraphControl flameGraphControl;

    @Inject
    private StreamThroughputControl throughputControl;

    public TelemetryResource() {
    }

    public TelemetryResource(TelemetryControl telemetryControl) {
        this(telemetryControl, null, null);
    }

    public TelemetryResource(TelemetryControl telemetryControl, FlameGraphControl flameGraphControl) {
        this(telemetryControl, flameGraphControl, null);
    }

    public TelemetryResource(TelemetryControl telemetryControl, FlameGraphControl flameGraphControl,
                             StreamThroughputControl throughputControl) {
        this.telemetryControl = telemetryControl;
        this.flameGraphControl = flameGraphControl;
        this.throughputControl = throughputControl;
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
    @Operation(summary = "Stream live JVM telemetry via SSE (ADMIN)", description = "Establishes a Server-Sent Events stream broadcasting live HotSpot memory, GC, and thread metrics every 1 second")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE stream established successfully"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
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
    @Operation(summary = "Get JVM telemetry snapshot (ADMIN)", description = "Returns an instantaneous snapshot of JVM heap, non-heap, GC collections, and thread counts")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Current JVM telemetry snapshot"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    })
    public Response getSnapshot() {
        if (telemetryControl == null) {
            return Response.serverError().build();
        }
        JvmTelemetrySnapshot snapshot = telemetryControl.currentSnapshot();
        return Response.ok(snapshot).build();
    }

    /**
     * Point-in-time folded stack trace flame graph telemetry.
     * Supports plain-text folded format (compatible with Brendan Gregg tools, FlameScope, and Speedscope),
     * d3-flame-graph JSON hierarchy, Speedscope JSON schema, and self-contained interactive HTML.
     *
     * @param metricParam metric dimension (CPU_TIME or ALLOCATION_BYTES, default: CPU_TIME)
     * @param formatParam output format (text, json, d3, speedscope, html)
     * @param headers     HTTP request headers for content negotiation
     * @return 200 OK with formatted flame graph telemetry
     */
    @GET
    @Path("/flamegraph")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.TEXT_HTML})
    @RolesAllowed({"ADMIN", "ENGINEER"})
    @Operation(summary = "Get folded stack flame graph telemetry",
            description = "Returns aggregated call stack traces in folded text, d3-flame-graph JSON, Speedscope JSON, or HTML format")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Folded stack flame graph data"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN, ENGINEER, or ANALYST role")
    })
    public Response getFlameGraph(
            @QueryParam("metric") @DefaultValue("CPU_TIME") String metricParam,
            @QueryParam("format") String formatParam,
            @Context HttpHeaders headers) {
        if (flameGraphControl == null) {
            return Response.serverError().build();
        }

        MetricType metric = parseMetric(metricParam);

        if (formatParam != null && !formatParam.isBlank()) {
            String f = formatParam.trim().toLowerCase();
            if ("text".equals(f) || "folded".equals(f)) {
                return Response.ok(flameGraphControl.getFoldedText(metric), MediaType.TEXT_PLAIN).build();
            } else if ("d3".equals(f)) {
                return Response.ok(flameGraphControl.getD3Tree(metric), MediaType.APPLICATION_JSON).build();
            } else if ("speedscope".equals(f)) {
                return Response.ok(flameGraphControl.getSpeedscopeJson(metric), MediaType.APPLICATION_JSON).build();
            } else if ("html".equals(f)) {
                return Response.ok(flameGraphControl.getHtmlFlameGraph(metric), MediaType.TEXT_HTML).build();
            }
        } else if (headers != null && headers.getAcceptableMediaTypes() != null) {
            for (MediaType mt : headers.getAcceptableMediaTypes()) {
                if (mt.isCompatible(MediaType.TEXT_PLAIN_TYPE)) {
                    return Response.ok(flameGraphControl.getFoldedText(metric), MediaType.TEXT_PLAIN).build();
                } else if (mt.isCompatible(MediaType.TEXT_HTML_TYPE)) {
                    return Response.ok(flameGraphControl.getHtmlFlameGraph(metric), MediaType.TEXT_HTML).build();
                } else if (mt.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
                    break;
                }
            }
        }

        return Response.ok(flameGraphControl.getFlameGraphResponse(metric), MediaType.APPLICATION_JSON).build();
    }

    /**
     * Server-Sent Events endpoint streaming live folded stack trace flame graph telemetry.
     *
     * @param sink        client SSE event sink
     * @param sse         JAX-RS SSE context
     * @param metricParam metric dimension (CPU_TIME or ALLOCATION_BYTES, default: CPU_TIME)
     * @param formatParam format (json or text, default: json)
     */
    @GET
    @Path("/flamegraph/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"ADMIN", "ENGINEER"})
    @Operation(summary = "Stream live flame graph telemetry via SSE",
            description = "Establishes a Server-Sent Events stream broadcasting live folded stack trace updates")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE stream established successfully"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN, ENGINEER, or ANALYST role")
    })
    public void streamFlameGraph(
            @Context SseEventSink sink,
            @Context Sse sse,
            @QueryParam("metric") @DefaultValue("CPU_TIME") String metricParam,
            @QueryParam("format") @DefaultValue("json") String formatParam) {
        if (sink != null && flameGraphControl != null) {
            MetricType metric = parseMetric(metricParam);
            flameGraphControl.registerSink(sink, sse, metric, formatParam);
        }
    }

    private MetricType parseMetric(String metricParam) {
        if (metricParam == null || metricParam.isBlank()) {
            return MetricType.CPU_TIME;
        }
        try {
            return MetricType.valueOf(metricParam.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MetricType.CPU_TIME;
        }
    }

    /**
     * Server-Sent Events endpoint streaming real-time streaming throughput,
     * queue depth, and P99 latency every 1,000 ms.
     *
     * @param sink client SSE event sink
     * @param sse JAX-RS SSE context
     */
    @GET
    @Path("/stream/throughput")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"ADMIN", "ENGINEER", "OPERATOR"})
    @Operation(summary = "Stream real-time throughput telemetry via SSE",
            description = "Establishes a Server-Sent Events stream broadcasting events/sec, queue depth, and P99 latency every 1,000 ms")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE throughput stream established successfully"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN, ENGINEER, or OPERATOR role")
    })
    public void streamThroughput(@Context SseEventSink sink, @Context Sse sse) {
        if (sink != null && throughputControl != null) {
            throughputControl.registerSink(sink, sse);
        }
    }

    /**
     * Point-in-time snapshot of stream throughput, queue depth, and P99 latency.
     *
     * @return 200 OK with StreamThroughputSnapshot
     */
    @GET
    @Path("/stream/throughput/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"ADMIN", "ENGINEER", "OPERATOR"})
    @Operation(summary = "Get stream throughput snapshot",
            description = "Returns an instantaneous snapshot of streaming events/sec, queue depth, and P99 latency")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Current throughput snapshot"),
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @APIResponse(responseCode = "403", description = "Forbidden - requires ADMIN, ENGINEER, or OPERATOR role")
    })
    public Response getThroughputSnapshot() {
        if (throughputControl == null) {
            return Response.serverError().build();
        }
        return Response.ok(throughputControl.currentSnapshot()).build();
    }

    public void setTelemetryControl(TelemetryControl telemetryControl) {
        this.telemetryControl = telemetryControl;
    }

    public FlameGraphControl getFlameGraphControl() {
        return flameGraphControl;
    }

    public void setFlameGraphControl(FlameGraphControl flameGraphControl) {
        this.flameGraphControl = flameGraphControl;
    }

    public StreamThroughputControl getThroughputControl() {
        return throughputControl;
    }

    public void setThroughputControl(StreamThroughputControl throughputControl) {
        this.throughputControl = throughputControl;
    }
}
