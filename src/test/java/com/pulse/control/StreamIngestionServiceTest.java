package com.pulse.control;

import com.pulse.boundary.dto.BatchStreamIngestRequest;
import com.pulse.boundary.dto.BatchStreamIngestResponse;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamIngestResponse;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Task: Stream Ingestion Service Unit Tests")
class StreamIngestionServiceTest {

    private MockProducer<String, byte[]> mockProducer;
    private StreamIngestionService service;

    @BeforeEach
    void setUp() {
        mockProducer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        service = new StreamIngestionService(mockProducer, "rules.input");
    }

    @Test
    @DisplayName("Should ingest single event and acknowledge with 202 ACCEPTED status")
    void testSingleIngest() {
        StreamIngestRequest request = new StreamIngestRequest(
                "evt-100",
                "orders.input",
                "FraudCheckRule",
                Map.of("amount", 250.0, "currency", "USD"),
                Map.of("source", "mobile-app")
        );

        StreamIngestResponse response = service.ingest(request);

        assertThat(response).isNotNull();
        assertThat(response.eventId()).isEqualTo("evt-100");
        assertThat(response.topic()).isEqualTo("orders.input");
        assertThat(response.ruleName()).isEqualTo("FraudCheckRule");
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.timestamp()).isGreaterThan(0);

        assertThat(service.getTotalIngested()).isEqualTo(1L);
        assertThat(service.getTotalFailed()).isEqualTo(0L);

        assertThat(mockProducer.history()).hasSize(1);
        var record = mockProducer.history().get(0);
        assertThat(record.topic()).isEqualTo("orders.input");
    }

    @Test
    @DisplayName("Should generate UUID and fallback to default input topic when not provided")
    void testIngestWithDefaults() {
        StreamIngestRequest request = new StreamIngestRequest(
                null,
                null,
                "OrderValidation",
                Map.of("user", "Alice"),
                null
        );

        StreamIngestResponse response = service.ingest(request);

        assertThat(response.eventId()).isNotBlank();
        assertThat(response.topic()).isEqualTo("rules.input");
        assertThat(response.ruleName()).isEqualTo("OrderValidation");
        assertThat(response.status()).isEqualTo("ACCEPTED");

        assertThat(mockProducer.history()).hasSize(1);
        assertThat(mockProducer.history().get(0).topic()).isEqualTo("rules.input");
    }

    @Test
    @DisplayName("Should reject null ingest request with NullPointerException")
    void testNullRequestValidation() {
        assertThatThrownBy(() -> service.ingest(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should ingest batch of events and report accepted count")
    void testBatchIngest() {
        List<StreamIngestRequest> events = List.of(
                new StreamIngestRequest("e1", "rules.input", "RuleA", Map.of("x", 1), null),
                new StreamIngestRequest("e2", "rules.input", "RuleB", Map.of("x", 2), null),
                new StreamIngestRequest("e3", "rules.input", "RuleC", Map.of("x", 3), null)
        );

        BatchStreamIngestRequest batchRequest = new BatchStreamIngestRequest(events);
        BatchStreamIngestResponse batchResponse = service.ingestBatch(batchRequest);

        assertThat(batchResponse.total()).isEqualTo(3);
        assertThat(batchResponse.accepted()).isEqualTo(3);
        assertThat(batchResponse.results()).hasSize(3);
        assertThat(service.getTotalIngested()).isEqualTo(3L);
        assertThat(mockProducer.history()).hasSize(3);
    }
}
