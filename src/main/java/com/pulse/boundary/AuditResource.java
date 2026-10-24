package com.pulse.boundary;

import com.pulse.audit.AuditRecord;
import com.pulse.audit.AuditRecordRepository;
import com.pulse.audit.AuditTrailService;
import com.pulse.audit.MerkleChainAppender;
import com.pulse.audit.MerkleRoot;
import com.pulse.boundary.dto.AuditVerificationResponse;
import com.pulse.boundary.dto.PaginatedAuditResponse;
import com.pulse.boundary.dto.ProblemDetail;
import com.pulse.boundary.dto.SignMerkleRootRequest;
import com.pulse.boundary.dto.SignedMerkleRootResponse;
import com.pulse.boundary.filter.Secured;
import com.pulse.entity.EngineRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
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
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Enterprise JAX-RS REST boundary exposing endpoints for the cryptographic audit trail,
 * Merkle root checkpoint publication, and historical record integrity verification.
 *
 * <p>Secured via MicroProfile JWT authentication and role-based access control (RBAC).
 * Supports RFC 7807 problem details for structured diagnostic reporting on failure.</p>
 */
@Path("/audit")
@Tag(name = "audit", description = "Cryptographic audit trail, Merkle root signing, and integrity verification")
@Secured
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class AuditResource {

    private static final Logger log = LoggerFactory.getLogger(AuditResource.class);
    private static final int MAX_LIMIT = 200;

    @Inject
    private AuditRecordRepository auditRepository;

    @Inject
    private AuditTrailService auditTrailService;

    @Inject
    private MerkleChainAppender appender;

    @Context
    private UriInfo uriInfo;

    public AuditResource() {}

    public AuditResource(AuditRecordRepository auditRepository,
                         AuditTrailService auditTrailService,
                         MerkleChainAppender appender) {
        this.auditRepository = auditRepository;
        this.auditTrailService = auditTrailService;
        this.appender = appender;
    }

    /**
     * Retrieves paginated audit records, optionally filtered by rule name.
     */
    @GET
    @Path("/records")
    @RolesAllowed({"ENGINEER", "OPERATOR", "DATA_SCIENTIST", "ADMIN"})
    @Operation(summary = "Query audit records", description = "Returns a paginated list of immutable rule execution audit records.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Paginated audit records retrieved",
                    content = @Content(schema = @Schema(implementation = PaginatedAuditResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid pagination query parameters",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Response getRecords(
            @Parameter(description = "Optional rule name filter") @QueryParam("ruleName") String ruleName,
            @Parameter(description = "Page size (1-200, default: 50)") @QueryParam("limit") @DefaultValue("50") int limit,
            @Parameter(description = "Zero-based page index (default: 0)") @QueryParam("page") @DefaultValue("0") int page) {

        if (limit < 1 || limit > MAX_LIMIT) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Invalid Pagination Limit",
                            "Query parameter 'limit' must be between 1 and " + MAX_LIMIT))
                    .build();
        }

        if (page < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Invalid Pagination Page",
                            "Query parameter 'page' cannot be negative"))
                    .build();
        }

        int offset = page * limit;
        List<AuditRecord> records = auditRepository.findPaginated(ruleName, offset, limit);
        long totalCount = auditRepository.countByRuleName(ruleName);
        int totalPages = (int) Math.ceil((double) totalCount / limit);

        PaginatedAuditResponse<AuditRecord> response = new PaginatedAuditResponse<>(
                records, page, limit, totalCount, totalPages);

        return Response.ok(response).build();
    }

    /**
     * Retrieves a single audit record by its unique sequence number.
     */
    @GET
    @Path("/records/{seq}")
    @RolesAllowed({"ENGINEER", "OPERATOR", "DATA_SCIENTIST", "ADMIN"})
    @Operation(summary = "Get audit record by sequence", description = "Retrieves an immutable audit record by its monotonic sequence number.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Audit record found",
                    content = @Content(schema = @Schema(implementation = AuditRecord.class))),
            @APIResponse(responseCode = "400", description = "Invalid sequence parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @APIResponse(responseCode = "404", description = "Audit record not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Response getRecord(
            @Parameter(description = "Audit record monotonic sequence number") @PathParam("seq") long seq) {

        if (seq <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Invalid Sequence Number",
                            "Path parameter 'seq' must be greater than zero"))
                    .build();
        }

        Optional<AuditRecord> recordOpt = auditRepository.findBySeq(seq);
        if (recordOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ProblemDetail(
                            Response.Status.NOT_FOUND.getStatusCode(),
                            "Audit Record Not Found",
                            "No audit record found for sequence number " + seq))
                    .build();
        }

        return Response.ok(recordOpt.get()).build();
    }

    /**
     * Signs and publishes a cryptographic Merkle root checkpoint for the given sequence bounds.
     */
    @POST
    @Path("/sign")
    @RolesAllowed({"ADMIN", "OPERATOR"})
    @Operation(summary = "Sign Merkle root checkpoint", description = "Computes and signs an Ed25519 digital signature over the Merkle root hash for a sequence range.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Merkle root checkpoint signed and published",
                    content = @Content(schema = @Schema(implementation = SignedMerkleRootResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid sequence bounds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @APIResponse(responseCode = "404", description = "Target sequence boundary not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @APIResponse(responseCode = "500", description = "Cryptographic signing error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Response sign(
            @RequestBody(description = "Sequence range bounds for Merkle root checkpoint", required = true)
            SignMerkleRootRequest request) {

        if (request == null || request.fromSeq() == null || request.toSeq() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Missing Sequence Range",
                            "Both 'fromSeq' and 'toSeq' are required fields"))
                    .build();
        }

        if (request.fromSeq() <= 0 || request.toSeq() <= 0 || request.fromSeq() > request.toSeq()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Invalid Sequence Bounds",
                            "fromSeq must be >= 1 and fromSeq must be <= toSeq"))
                    .build();
        }

        try {
            MerkleRoot root = auditTrailService.publishMerkleRoot(request.fromSeq(), request.toSeq());
            SignedMerkleRootResponse response = new SignedMerkleRootResponse(
                    root.getRootHash(),
                    root.getSignatureB64(),
                    root.getPublicKeyB64(),
                    root.getFromSeq(),
                    root.getToSeq(),
                    root.getPublishedAt()
            );
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            log.warn("Cannot sign Merkle root for range [{}-{}]: {}", request.fromSeq(), request.toSeq(), e.getMessage());
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ProblemDetail(
                            Response.Status.NOT_FOUND.getStatusCode(),
                            "Boundary Record Not Found",
                            e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Cryptographic signing error during Merkle root checkpoint publication", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ProblemDetail(
                            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                            "Signing Failure",
                            "Failed to generate Ed25519 digital signature: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Verifies the cryptographic integrity of an audit record by sequence number.
     */
    @GET
    @Path("/verify")
    @RolesAllowed({"ENGINEER", "OPERATOR", "DATA_SCIENTIST", "ADMIN"})
    @Operation(summary = "Verify audit record integrity", description = "Recomputes the cryptographic leaf hash from stored payload and previous leaf hash to prove tamper resistance.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Cryptographic integrity verification report",
                    content = @Content(schema = @Schema(implementation = AuditVerificationResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid sequence parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @APIResponse(responseCode = "404", description = "Audit record not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Response verify(
            @Parameter(description = "Sequence number of the record to verify") @QueryParam("seq") long seq) {

        if (seq <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ProblemDetail(
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Invalid Sequence Number",
                            "Query parameter 'seq' must be greater than zero"))
                    .build();
        }

        Optional<AuditRecord> recordOpt = auditRepository.findBySeq(seq);
        if (recordOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ProblemDetail(
                            Response.Status.NOT_FOUND.getStatusCode(),
                            "Audit Record Not Found",
                            "No audit record found for sequence number " + seq))
                    .build();
        }

        AuditRecord record = recordOpt.get();
        String computedLeaf = appender.appendLeaf(record.getPrevHash(), record.getEventHash());
        boolean verified = computedLeaf.equalsIgnoreCase(record.getLeafHash());
        String integrityStatus = verified ? "VALID" : "TAMPERED";

        AuditVerificationResponse report = new AuditVerificationResponse(
                record.getRecordSeq(),
                record.getRuleName(),
                record.getRuleVersion(),
                record.getTenantId(),
                record.getExecutionResult(),
                record.getDurationNanos(),
                record.getEventHash(),
                record.getPrevHash() != null ? record.getPrevHash() : "GENESIS",
                record.getLeafHash(),
                record.getEvaluatedAt(),
                record.isSandboxRun(),
                verified,
                computedLeaf,
                integrityStatus
        );

        return Response.ok(report).build();
    }
}
