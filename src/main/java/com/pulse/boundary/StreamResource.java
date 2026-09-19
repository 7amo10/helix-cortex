package com.pulse.boundary;

import com.pulse.boundary.dto.BatchStreamIngestRequest;
import com.pulse.boundary.dto.BatchStreamIngestResponse;
import com.pulse.boundary.dto.StreamCoordinatorStats;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamIngestResponse;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.StreamConsumerCoordinator;
import com.pulse.control.StreamExecutionRepository;
import com.pulse.control.StreamIngestionService;
import com.pulse.entity.StreamExecutionRecord;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;

/**
 * REST boundary exposing the high-throughput streaming ingestion gateway and
 * stream execution analytics. Secured with MicroProfile JWT role-based access control.
 */
@Path("/stream")
@Tag(name = "stream", description = "High-throughput streaming ingestion and persistence coordinator")
@Secured
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class StreamResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private StreamIngestionService ingestionService;

    @Inject
    private StreamConsumerCoordinator coordinator;

    @Inject
    private StreamExecutionRepository repository;

    @Inject
    private com.pulse.control.StreamThroughputControl throughputControl;

    @Context
    private SecurityContext securityContext;

    public StreamResource() {
    }

    public StreamResource(StreamIngestionService ingestionService, StreamConsumerCoordinator coordinator,
                          StreamExecutionRepository repository, SecurityContext securityContext) {
        this(ingestionService, coordinator, repository, null, securityContext);
    }

    public StreamResource(StreamIngestionService ingestionService, StreamConsumerCoordinator coordinator,
                          StreamExecutionRepository repository, com.pulse.control.StreamThroughputControl throughputControl,
                          SecurityContext securityContext) {
        this.ingestionService = ingestionService;
        this.coordinator = coordinator;
        this.repository = repository;
        this.throughputControl = throughputControl;
        this.securityContext = securityContext;
    }

    @POST
    @Path("/ingest")
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Ingest event into Kafka streaming pipeline",
            description = "Rapidly ingests an event into Kafka topics for asynchronous rule evaluation and batched persistence")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Event accepted and published to Kafka stream"),
            @APIResponse(responseCode = "400", description = "Missing or malformed request payload")
    })
    public Response ingest(StreamIngestRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Request payload is required\"}")
                    .build();
        }

        if (ingestionService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"StreamIngestionService is not available\"}")
                    .build();
        }

        StreamIngestResponse response = ingestionService.ingest(request);
        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/ingest/batch")
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Batch ingest events into Kafka stream",
            description = "High-throughput ingestion of multiple events into Kafka streaming topics")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Batch accepted for streaming processing"),
            @APIResponse(responseCode = "400", description = "Missing or empty batch payload")
    })
    public Response ingestBatch(BatchStreamIngestRequest batchRequest) {
        if (batchRequest == null || batchRequest.events() == null || batchRequest.events().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"events array is required and cannot be empty\"}")
                    .build();
        }

        if (ingestionService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"StreamIngestionService is not available\"}")
                    .build();
        }

        BatchStreamIngestResponse response = ingestionService.ingestBatch(batchRequest);
        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @GET
    @Path("/stats")
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Get stream ingestion and persistence telemetry",
            description = "Returns real-time counts of ingested, consumed, and batched persisted events")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Coordinator telemetry statistics")
    })
    public Response getStats() {
        long totalIngested = ingestionService != null ? ingestionService.getTotalIngested() : 0L;
        StreamCoordinatorStats stats = coordinator != null ? coordinator.getStats() :
                new StreamCoordinatorStats(0L, 0L, 0L, 0L, false, "rules.input", "rules.results");

        StreamCoordinatorStats aggregated = new StreamCoordinatorStats(
                totalIngested,
                stats.totalConsumed(),
                stats.totalPersisted(),
                stats.batchesPersisted(),
                stats.running(),
                stats.inputTopic(),
                stats.resultsTopic()
        );
        return Response.ok(aggregated).build();
    }

    @GET
    @Path("/records")
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "List persisted stream execution records",
            description = "Retrieves persisted stream execution records with eager EntityGraph metrics")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of persisted execution records")
    })
    public Response getRecords() {
        List<StreamExecutionRecord> records = repository != null ? repository.findAllWithMetrics() : List.of();
        return Response.ok(records).build();
    }

    @GET
    @Path("/records/{eventId}")
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Get persisted record by event ID",
            description = "Retrieves a single stream execution record and its metrics by event identifier")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Record found"),
            @APIResponse(responseCode = "404", description = "Record not found for given eventId")
    })
    public Response getRecordByEventId(@PathParam("eventId") String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"eventId is required\"}")
                    .build();
        }

        Optional<StreamExecutionRecord> record = repository != null ? repository.findByEventId(eventId) : Optional.empty();
        if (record.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(PROBLEM_JSON)
                    .entity(String.format("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"Record not found for eventId: %s\"}", eventId))
                    .build();
        }

        return Response.ok(record.get()).build();
    }

    @GET
    @Path("/throughput")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Stream throughput telemetry via SSE",
            description = "Establishes a Server-Sent Events stream broadcasting events/sec, queue depth, and P99 latency every 1,000 ms")
    public void streamThroughput(@Context jakarta.ws.rs.sse.SseEventSink sink, @Context jakarta.ws.rs.sse.Sse sse) {
        if (sink != null && throughputControl != null) {
            throughputControl.registerSink(sink, sse);
        }
    }

    @GET
    @Path("/throughput/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"ENGINEER", "OPERATOR", "ADMIN"})
    @Operation(summary = "Get current throughput snapshot",
            description = "Returns current snapshot of events/sec, queue depth, and P99 latency")
    public Response getThroughputSnapshot() {
        if (throughputControl == null) {
            return Response.serverError().build();
        }
        return Response.ok(throughputControl.currentSnapshot()).build();
    }

    public void setThroughputControl(com.pulse.control.StreamThroughputControl throughputControl) {
        this.throughputControl = throughputControl;
    }

    public void setIngestionService(StreamIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void setCoordinator(StreamConsumerCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setRepository(StreamExecutionRepository repository) {
        this.repository = repository;
    }

    public void setSecurityContext(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }
}
