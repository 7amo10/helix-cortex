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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditResource REST Boundary Unit Tests")
class AuditResourceTest {

    @Mock
    private AuditRecordRepository auditRepository;

    @Mock
    private AuditTrailService auditTrailService;

    @Mock
    private MerkleChainAppender appender;

    @InjectMocks
    private AuditResource auditResource;

    private AuditRecord sampleRecord;

    @BeforeEach
    void setUp() {
        sampleRecord = AuditRecord.builder()
                .recordSeq(1L)
                .ruleName("CreditAssessment")
                .ruleVersion("1.0.0")
                .tenantId("tenant-finance")
                .executionResult("PASSED")
                .durationNanos(120_000L)
                .eventJson("{\"status\":\"ok\"}")
                .eventHash("e123")
                .prevHash(null)
                .leafHash("leaf123")
                .isSandboxRun(false)
                .evaluatedAt(OffsetDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/audit/records (Listing)")
    class GetRecordsTests {

        @Test
        @DisplayName("Returns paginated audit records with HTTP 200")
        void testGetRecords_paginatedSuccess() {
            when(auditRepository.findPaginated(eq("CreditAssessment"), eq(0), eq(10)))
                    .thenReturn(List.of(sampleRecord));
            when(auditRepository.countByRuleName(eq("CreditAssessment"))).thenReturn(1L);

            Response response = auditResource.getRecords("CreditAssessment", 10, 0);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            @SuppressWarnings("unchecked")
            PaginatedAuditResponse<AuditRecord> body = (PaginatedAuditResponse<AuditRecord>) response.getEntity();
            assertNotNull(body);
            assertEquals(1, body.records().size());
            assertEquals(1L, body.totalCount());
            assertEquals(0, body.page());
            assertEquals(10, body.limit());
            assertEquals(1, body.totalPages());
        }

        @Test
        @DisplayName("Returns HTTP 400 when invalid limit or page parameters provided")
        void testGetRecords_invalidPaginationParameters() {
            Response resp1 = auditResource.getRecords(null, 0, 0);
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp1.getStatus());

            Response resp2 = auditResource.getRecords(null, 10, -1);
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp2.getStatus());

            Response resp3 = auditResource.getRecords(null, 1000, 0);
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp3.getStatus());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/audit/records/{seq} (Single Record)")
    class GetSingleRecordTests {

        @Test
        @DisplayName("Returns HTTP 200 with record when found by sequence")
        void testGetRecord_found() {
            when(auditRepository.findBySeq(1L)).thenReturn(Optional.of(sampleRecord));

            Response response = auditResource.getRecord(1L);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals(sampleRecord, response.getEntity());
        }

        @Test
        @DisplayName("Returns HTTP 404 when record does not exist for sequence")
        void testGetRecord_notFound() {
            when(auditRepository.findBySeq(999L)).thenReturn(Optional.empty());

            Response response = auditResource.getRecord(999L);

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof ProblemDetail);
        }

        @Test
        @DisplayName("Returns HTTP 400 when invalid sequence <= 0 is passed")
        void testGetRecord_invalidSeq() {
            Response response = auditResource.getRecord(0L);

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/audit/sign (Merkle Root Checkpoint)")
    class SignMerkleRootTests {

        @Test
        @DisplayName("Publishes and signs Merkle root, returning HTTP 200 with signature payload")
        void testSign_success() throws Exception {
            SignMerkleRootRequest request = new SignMerkleRootRequest(1L, 100L);
            MerkleRoot root = MerkleRoot.builder()
                    .rootHash("rootHash999")
                    .signatureB64("sigBase64==")
                    .publicKeyB64("pubKeyBase64==")
                    .fromSeq(1L)
                    .toSeq(100L)
                    .publishedAt(OffsetDateTime.now())
                    .build();

            when(auditTrailService.publishMerkleRoot(1L, 100L)).thenReturn(root);

            Response response = auditResource.sign(request);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof SignedMerkleRootResponse);
            SignedMerkleRootResponse body = (SignedMerkleRootResponse) response.getEntity();
            assertEquals("rootHash999", body.rootHash());
            assertEquals("sigBase64==", body.signatureB64());
            assertEquals("pubKeyBase64==", body.publicKeyB64());
            assertEquals(1L, body.fromSeq());
            assertEquals(100L, body.toSeq());
        }

        @Test
        @DisplayName("Returns HTTP 400 when request is null, bounds missing, or fromSeq > toSeq")
        void testSign_validationFailure() {
            Response respNull = auditResource.sign(null);
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), respNull.getStatus());

            Response respNegative = auditResource.sign(new SignMerkleRootRequest(0L, 10L));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), respNegative.getStatus());

            Response respInverted = auditResource.sign(new SignMerkleRootRequest(50L, 10L));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), respInverted.getStatus());
        }

        @Test
        @DisplayName("Returns HTTP 404 when sequence boundary record is not found in database")
        void testSign_targetSequenceNotFound() throws Exception {
            SignMerkleRootRequest request = new SignMerkleRootRequest(1L, 999L);
            when(auditTrailService.publishMerkleRoot(1L, 999L))
                    .thenThrow(new IllegalArgumentException("No audit record found for toSeq=999"));

            Response response = auditResource.sign(request);

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof ProblemDetail);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/audit/verify (Cryptographic Verification)")
    class VerifyRecordTests {

        @Test
        @DisplayName("Returns verified=true when computed leaf hash matches stored leaf hash")
        void testVerify_validIntegrity() {
            when(auditRepository.findBySeq(1L)).thenReturn(Optional.of(sampleRecord));
            when(appender.appendLeaf(null, "e123")).thenReturn("leaf123");

            Response response = auditResource.verify(1L);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof AuditVerificationResponse);
            AuditVerificationResponse body = (AuditVerificationResponse) response.getEntity();
            assertTrue(body.verified());
            assertEquals("VALID", body.integrityStatus());
            assertEquals("leaf123", body.leafHash());
            assertEquals("leaf123", body.computedLeafHash());
            assertEquals("GENESIS", body.prevHash());
        }

        @Test
        @DisplayName("Returns verified=false and HTTP 200 with TAMPERED status when leaf hashes differ")
        void testVerify_tamperedIntegrity() {
            when(auditRepository.findBySeq(1L)).thenReturn(Optional.of(sampleRecord));
            when(appender.appendLeaf(null, "e123")).thenReturn("differentComputedLeaf");

            Response response = auditResource.verify(1L);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof AuditVerificationResponse);
            AuditVerificationResponse body = (AuditVerificationResponse) response.getEntity();
            assertFalse(body.verified());
            assertEquals("TAMPERED", body.integrityStatus());
            assertEquals("leaf123", body.leafHash());
            assertEquals("differentComputedLeaf", body.computedLeafHash());
        }

        @Test
        @DisplayName("Returns HTTP 404 when record to verify does not exist")
        void testVerify_recordNotFound() {
            when(auditRepository.findBySeq(500L)).thenReturn(Optional.empty());

            Response response = auditResource.verify(500L);

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
            assertTrue(response.getEntity() instanceof ProblemDetail);
        }

        @Test
        @DisplayName("Returns HTTP 400 when invalid sequence <= 0 passed")
        void testVerify_invalidSeq() {
            Response response = auditResource.verify(0L);

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        }
    }
}
