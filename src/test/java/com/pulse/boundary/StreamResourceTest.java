package com.pulse.boundary;

import com.pulse.boundary.dto.BatchStreamIngestRequest;
import com.pulse.boundary.dto.StreamCoordinatorStats;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamIngestResponse;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.StreamConsumerCoordinator;
import com.pulse.control.StreamExecutionRepository;
import com.pulse.control.StreamIngestionService;
import com.pulse.entity.StreamExecutionRecord;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Task: Stream Resource REST Boundary Unit Tests")
class StreamResourceTest {

    private StreamIngestionService ingestionService;
    private StreamConsumerCoordinator coordinator;
    private StreamExecutionRepository repository;
    private SecurityContext securityContext;
    private StreamResource resource;

    @BeforeEach
    void setUp() {
        ingestionService = mock(StreamIngestionService.class);
        coordinator = mock(StreamConsumerCoordinator.class);
        repository = mock(StreamExecutionRepository.class);
        securityContext = mock(SecurityContext.class);

        Principal principal = () -> "engineer_user";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole("ENGINEER")).thenReturn(true);

        resource = new StreamResource(ingestionService, coordinator, repository, securityContext);
    }

    @Test
    @DisplayName("StreamResource class should have @Secured and role annotations")
    void testSecurityAnnotations() throws Exception {
        assertThat(StreamResource.class.isAnnotationPresent(Secured.class)).isTrue();

        var ingestMethod = StreamResource.class.getMethod("ingest", StreamIngestRequest.class);
        assertThat(ingestMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(ingestMethod.getAnnotation(RolesAllowed.class).value())
                .containsExactlyInAnyOrder("ENGINEER", "OPERATOR", "ADMIN");

        var ingestBatchMethod = StreamResource.class.getMethod("ingestBatch", BatchStreamIngestRequest.class);
        assertThat(ingestBatchMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();

        var statsMethod = StreamResource.class.getMethod("getStats");
        assertThat(statsMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();

        var recordsMethod = StreamResource.class.getMethod("getRecords");
        assertThat(recordsMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
    }

    @Test
    @DisplayName("Should return 202 ACCEPTED when ingesting a valid stream request")
    void testIngestSuccess() {
        StreamIngestRequest req = new StreamIngestRequest("e-1", "rules.input", "FraudRule", Map.of("amt", 100), null);
        StreamIngestResponse mockResp = new StreamIngestResponse("e-1", "rules.input", "FraudRule", "ACCEPTED", System.currentTimeMillis());

        when(ingestionService.ingest(req)).thenReturn(mockResp);

        Response response = resource.ingest(req);

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getEntity()).isEqualTo(mockResp);
        verify(ingestionService, times(1)).ingest(req);
    }

    @Test
    @DisplayName("Should return 400 Bad Request when request payload is null")
    void testIngestNullPayload() {
        Response response = resource.ingest(null);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("Should return stats aggregated from ingestion and consumer coordinator")
    void testGetStats() {
        when(ingestionService.getTotalIngested()).thenReturn(150L);
        when(coordinator.getStats()).thenReturn(new StreamCoordinatorStats(
                0L, 120L, 120L, 5L, true, "rules.input", "rules.results"
        ));

        Response response = resource.getStats();

        assertThat(response.getStatus()).isEqualTo(200);
        StreamCoordinatorStats stats = (StreamCoordinatorStats) response.getEntity();
        assertThat(stats.totalIngested()).isEqualTo(150L);
        assertThat(stats.totalConsumed()).isEqualTo(120L);
        assertThat(stats.totalPersisted()).isEqualTo(120L);
        assertThat(stats.running()).isTrue();
    }

    @Test
    @DisplayName("Should return 200 OK with persisted records")
    void testGetRecords() {
        StreamExecutionRecord r = new StreamExecutionRecord("e1", "rules.input", "Rule1", true, "RES", null, 100L);
        when(repository.findAllWithMetrics()).thenReturn(List.of(r));

        Response response = resource.getRecords();

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<StreamExecutionRecord> list = (List<StreamExecutionRecord>) response.getEntity();
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("Should return record by event ID or 404 if not found")
    void testGetRecordByEventId() {
        StreamExecutionRecord r = new StreamExecutionRecord("e-find", "rules.input", "Rule1", true, "RES", null, 100L);
        when(repository.findByEventId("e-find")).thenReturn(Optional.of(r));
        when(repository.findByEventId("not-found")).thenReturn(Optional.empty());

        Response found = resource.getRecordByEventId("e-find");
        assertThat(found.getStatus()).isEqualTo(200);
        assertThat(found.getEntity()).isEqualTo(r);

        Response notFound = resource.getRecordByEventId("not-found");
        assertThat(notFound.getStatus()).isEqualTo(404);
    }
}
