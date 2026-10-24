package com.pulse.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditTrailService Orchestrator Tests")
class AuditTrailServiceTest {

    @Mock
    private AuditRecordRepository auditRepository;

    @Mock
    private MerkleRootRepository merkleRootRepository;

    @Mock
    private AuditEventSerializer serializer;

    @Mock
    private MerkleChainAppender appender;

    @Mock
    private Ed25519Signer signer;

    @InjectMocks
    private AuditTrailService auditTrailService;

    @BeforeEach
    void setUp() {
        // Any common setup
    }

    @Test
    @DisplayName("recordExecution resolves prevHash, serializes canonical event, chains leaf, and persists record")
    void testRecordExecution_computesLeafHashAndChains() {
        when(auditRepository.findLatest()).thenReturn(Optional.empty());
        when(serializer.toCanonicalJson(any(), any(), any(), any(), anyLong())).thenReturn("{\"canonical\":true}");
        when(serializer.sha256Hex("{\"canonical\":true}")).thenReturn("eventHash123");
        when(appender.appendLeaf(null, "eventHash123")).thenReturn("leafHash123");

        AuditRecord result = auditTrailService.recordExecution(
                "RiskRule",
                "1.0.0",
                "tenant-1",
                "PASSED",
                150_000L,
                false
        );

        assertNotNull(result);
        assertEquals("leafHash123", result.getLeafHash());
        assertEquals("eventHash123", result.getEventHash());
        assertNull(result.getPrevHash());
        assertEquals("PASSED", result.getExecutionResult());

        verify(auditRepository, times(1)).save(any(AuditRecord.class));
    }

    @Test
    @DisplayName("recordExecution chains consecutive records using latest leafHash as prevHash")
    void testRecordExecution_chainsWithPreviousRecord() {
        AuditRecord prevRecord = AuditRecord.builder()
                .recordSeq(10L)
                .leafHash("leafPrev123")
                .build();

        when(auditRepository.findLatest()).thenReturn(Optional.of(prevRecord));
        when(serializer.toCanonicalJson(any(), any(), any(), any(), anyLong())).thenReturn("{\"canonical\":2}");
        when(serializer.sha256Hex("{\"canonical\":2}")).thenReturn("eventHash456");
        when(appender.appendLeaf("leafPrev123", "eventHash456")).thenReturn("leafHash456");

        AuditRecord result = auditTrailService.recordExecution(
                "FraudRule",
                "2.1.0",
                "tenant-2",
                "FAILED",
                250_000L,
                true
        );

        assertNotNull(result);
        assertEquals("leafHash456", result.getLeafHash());
        assertEquals("leafPrev123", result.getPrevHash());
        assertTrue(result.isSandboxRun());

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRepository).save(captor.capture());
        AuditRecord saved = captor.getValue();
        assertEquals("leafPrev123", saved.getPrevHash());
        assertEquals("leafHash456", saved.getLeafHash());
    }

    @Test
    @DisplayName("publishMerkleRoot signs root hash with Ed25519 and saves MerkleRoot checkpoint")
    void testPublishMerkleRoot_signsAndPersistsRoot() throws Exception {
        AuditRecord lastRecord = AuditRecord.builder()
                .recordSeq(100L)
                .leafHash("leafAtSeq100")
                .build();

        when(auditRepository.findBySeq(100L)).thenReturn(Optional.of(lastRecord));
        when(signer.sign("leafAtSeq100".getBytes())).thenReturn(new byte[]{1, 2, 3});
        when(signer.getPublicKeyBase64()).thenReturn("base64PublicKey==");

        MerkleRoot root = auditTrailService.publishMerkleRoot(1L, 100L);

        assertNotNull(root);
        assertEquals("leafAtSeq100", root.getRootHash());
        assertEquals("base64PublicKey==", root.getPublicKeyB64());
        assertEquals(1L, root.getFromSeq());
        assertEquals(100L, root.getToSeq());

        verify(merkleRootRepository, times(1)).save(root);
    }

    @Test
    @DisplayName("publishMerkleRoot throws IllegalArgumentException if target record is missing")
    void testPublishMerkleRoot_missingRecordThrowsException() {
        when(auditRepository.findBySeq(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> auditTrailService.publishMerkleRoot(1L, 999L));
        verify(merkleRootRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordExecution swallows internal exceptions so active rule execution is never disrupted")
    void testRecordExecution_faultTolerantOnRepositoryFailure() {
        when(auditRepository.findLatest()).thenThrow(new RuntimeException("Database offline"));

        assertDoesNotThrow(() -> {
            AuditRecord record = auditTrailService.recordExecution(
                    "RuleX",
                    "1.0",
                    "tenant-x",
                    "PASSED",
                    100L,
                    false
            );
            assertNull(record);
        });
    }
}
