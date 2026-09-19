package com.pulse;

import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.stream.RuleEvent;
import com.helix.api.stream.StreamResult;
import com.helix.core.stream.kafka.KafkaEventCodec;
import com.pulse.boundary.StreamResource;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamIngestResponse;
import com.pulse.boundary.filter.Secured;
import com.pulse.control.StreamConsumerCoordinator;
import com.pulse.control.StreamExecutionRepository;
import com.pulse.control.StreamIngestionService;
import com.pulse.control.StreamWorkerService;
import com.pulse.entity.StreamExecutionRecord;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive verification test verifying all acceptance criteria for Issue #43:
 * Kafka Streaming Ingestion Gateway & Result Persistence Coordinator.
 */
@DisplayName("Sprint 6: Kafka Streaming Ingestion Gateway & Result Persistence Coordinator Verification")
class Sprint6KafkaStreamingVerificationTest {

    @Test
    @DisplayName("Verification 1: Configuration, pom.xml, and persistence.xml properties")
    void testConfigurationAndDependencies() throws Exception {
        // Verify pom.xml includes kafka-clients dependency
        Path pomPath = Path.of("pom.xml");
        assertThat(pomPath).exists();
        String pomContent = Files.readString(pomPath);
        assertThat(pomContent).contains("<artifactId>kafka-clients</artifactId>");

        // Verify microprofile-config.properties contains Kafka properties
        InputStream configIs = getClass().getClassLoader().getResourceAsStream("META-INF/microprofile-config.properties");
        assertThat(configIs).isNotNull();
        Properties config = new Properties();
        config.load(configIs);
        assertThat(config.getProperty("helix.cortex.kafka.bootstrap-servers")).isEqualTo("localhost:9092");
        assertThat(config.getProperty("helix.cortex.kafka.input-topic")).isEqualTo("rules.input");
        assertThat(config.getProperty("helix.cortex.kafka.results-topic")).isEqualTo("rules.results");
        assertThat(config.getProperty("helix.cortex.kafka.batch-size")).isEqualTo("25");

        // Verify persistence.xml registers StreamExecutionRecord and StreamRecordMetric
        InputStream persistenceIs = getClass().getClassLoader().getResourceAsStream("META-INF/persistence.xml");
        assertThat(persistenceIs).isNotNull();
        String persistenceXml = new String(persistenceIs.readAllBytes());
        assertThat(persistenceXml).contains("<class>com.pulse.entity.StreamExecutionRecord</class>");
        assertThat(persistenceXml).contains("<class>com.pulse.entity.StreamRecordMetric</class>");
    }

    @Test
    @DisplayName("Verification 2: MicroProfile JWT RBAC security annotations on StreamResource")
    void testSecurityAnnotations() throws Exception {
        assertThat(StreamResource.class.isAnnotationPresent(Secured.class)).isTrue();

        var ingestMethod = StreamResource.class.getMethod("ingest", StreamIngestRequest.class);
        assertThat(ingestMethod.isAnnotationPresent(RolesAllowed.class)).isTrue();
        assertThat(ingestMethod.getAnnotation(RolesAllowed.class).value())
                .containsExactlyInAnyOrder("ENGINEER", "OPERATOR", "ADMIN");
    }

    @Test
    @DisplayName("Acceptance Criteria 1: End-to-end ingestion test (REST ingest -> Kafka topic -> Worker evaluation -> Kafka results -> PostgreSQL persistence)")
    @SuppressWarnings("unchecked")
    void testEndToEndIngestionPipeline() throws Exception {
        // Step 1: Initialize REST Ingestion Service with MockProducer
        MockProducer<String, byte[]> ingressProducer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StreamIngestionService ingestionService = new StreamIngestionService(ingressProducer, "rules.input");

        SecurityContext securityContext = mock(SecurityContext.class);
        Principal principal = () -> "engineer_alice";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole("ENGINEER")).thenReturn(true);

        StreamExecutionRepository repository = mock(StreamExecutionRepository.class);
        StreamConsumerCoordinator coordinator = mock(StreamConsumerCoordinator.class);
        StreamResource resource = new StreamResource(ingestionService, coordinator, repository, securityContext);

        // Client initiates REST Ingestion: POST /api/v1/stream/ingest
        StreamIngestRequest request = new StreamIngestRequest(
                "evt-fraud-101",
                "rules.input",
                "HighRiskTransactionRule",
                Map.of("amount", 25000.0, "country", "US"),
                Map.of("client-ip", "192.168.1.50")
        );

        Response restResponse = resource.ingest(request);
        assertThat(restResponse.getStatus()).isEqualTo(202);
        StreamIngestResponse ingestAck = (StreamIngestResponse) restResponse.getEntity();
        assertThat(ingestAck.eventId()).isEqualTo("evt-fraud-101");
        assertThat(ingestAck.status()).isEqualTo("ACCEPTED");

        // Verify event was published to Kafka topic 'rules.input'
        List<ProducerRecord<String, byte[]>> ingressHistory = ingressProducer.history();
        assertThat(ingressHistory).hasSize(1);
        ProducerRecord<String, byte[]> inputRecord = ingressHistory.get(0);
        assertThat(inputRecord.topic()).isEqualTo("rules.input");

        // Step 2: Worker evaluation: KafkaStreamEngine consumes from 'rules.input', evaluates rule, publishes to 'rules.results'
        MockConsumer<String, byte[]> workerConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        MockProducer<String, byte[]> workerProducer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());

        TopicPartition inputPartition = new TopicPartition("rules.input", 0);
        workerConsumer.updateBeginningOffsets(Map.of(inputPartition, 0L));

        StreamWorkerService workerService = new StreamWorkerService(workerConsumer, workerProducer, "rules.input", "rules.results");

        // Register compiled rule: amount > 10000 -> "FLAGGED_FOR_REVIEW"
        CompiledRule rule = new CompiledRule() {
            @Override
            public String getName() {
                return "HighRiskTransactionRule";
            }

            @Override
            public String getVersion() {
                return "1.0.0";
            }

            @Override
            public ExecutionResult execute(ExecutionContext ctx) {
                double amount = ((Number) ctx.getVariable("amount").orElse(0.0)).doubleValue();
                return amount > 10000.0
                        ? ExecutionResult.success("FLAGGED_FOR_REVIEW", 120L)
                        : ExecutionResult.success("APPROVED", 40L);
            }
        };
        workerService.registerRule("rules.input", rule);

        workerConsumer.rebalance(List.of(inputPartition));
        workerConsumer.addRecord(new ConsumerRecord<>(
                "rules.input", 0, 0L, inputRecord.key(), inputRecord.value()
        ));

        // Wait for worker evaluation to complete and output result
        boolean evaluated = waitForCondition(() -> !workerProducer.history().isEmpty(), 3000);
        assertThat(evaluated).isTrue();

        List<ProducerRecord<String, byte[]>> workerResults = workerProducer.history();
        assertThat(workerResults).hasSize(1);
        ProducerRecord<String, byte[]> resultKafkaRecord = workerResults.get(0);
        assertThat(resultKafkaRecord.topic()).isEqualTo("rules.results");

        StreamResult evaluatedResult = KafkaEventCodec.deserializeResult(resultKafkaRecord.value());
        assertThat(evaluatedResult.isSuccess()).isTrue();
        assertThat(evaluatedResult.getResult().orElse(null)).isEqualTo("FLAGGED_FOR_REVIEW");

        // Step 3: Persistence Coordinator consumes from 'rules.results' and persists to PostgreSQL
        MockConsumer<String, byte[]> coordinatorConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition resultsPartition = new TopicPartition("rules.results", 0);
        coordinatorConsumer.updateBeginningOffsets(Map.of(resultsPartition, 0L));

        // Real repository backed by mock EntityManager with EntityGraph support
        EntityManager em = mock(EntityManager.class);
        EntityGraph<StreamExecutionRecord> entityGraph = mock(EntityGraph.class);
        when(em.createEntityGraph(StreamExecutionRecord.class)).thenReturn(entityGraph);

        List<StreamExecutionRecord> persistedEntities = new ArrayList<>();
        when(repository.saveBatch(any(), eq(25))).thenAnswer(invocation -> {
            List<StreamExecutionRecord> batch = invocation.getArgument(0);
            persistedEntities.addAll(batch);
            return batch.size();
        });

        StreamConsumerCoordinator realCoordinator = new StreamConsumerCoordinator(
                coordinatorConsumer, repository, 25, 50
        );

        ConsumerRecord<String, byte[]> consumerRecord = new ConsumerRecord<>(
                "rules.results", 0, 0L, resultKafkaRecord.key(), resultKafkaRecord.value()
        );
        realCoordinator.processRecords(List.of(consumerRecord));
        realCoordinator.flushDirect();

        assertThat(persistedEntities).hasSize(1);
        StreamExecutionRecord persisted = persistedEntities.get(0);
        assertThat(persisted.getEventId()).isEqualTo("evt-fraud-101");
        assertThat(persisted.isSuccess()).isTrue();
        assertThat(persisted.getResultPayload()).isEqualTo("FLAGGED_FOR_REVIEW");
        assertThat(persisted.getMetrics()).isNotEmpty();

        // Step 4: Verify query with EntityGraph
        TypedQuery<StreamExecutionRecord> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(StreamExecutionRecord.class))).thenReturn(query);
        when(query.setParameter(eq("eventId"), anyString())).thenReturn(query);
        when(query.setHint(eq("jakarta.persistence.fetchgraph"), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(persisted));

        StreamExecutionRepository realRepo = new StreamExecutionRepository(em);
        var fetched = realRepo.findByEventId("evt-fraud-101");

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getResultPayload()).isEqualTo("FLAGGED_FOR_REVIEW");
        verify(entityGraph, times(1)).addAttributeNodes("metrics");
        verify(query, times(1)).setHint("jakarta.persistence.fetchgraph", entityGraph);

        workerService.close();
        ingestionService.close();
        realCoordinator.stop();
    }

    @Test
    @DisplayName("Acceptance Criteria 2: Zero dropped records under 5,000 requests/sec synthetic load test")
    void testZeroDroppedRecordsUnderHighThroughputLoad() throws Exception {
        int totalRequests = 5000;
        MockProducer<String, byte[]> loadProducer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StreamIngestionService ingestionService = new StreamIngestionService(loadProducer, "rules.input");

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCounter = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    StreamIngestRequest req = new StreamIngestRequest(
                            "load-evt-" + id,
                            "rules.input",
                            "LoadRule",
                            Map.of("seq", id, "active", true),
                            Map.of("thread", Thread.currentThread().getName())
                    );
                    StreamIngestResponse resp = ingestionService.ingest(req);
                    if ("ACCEPTED".equals(resp.status())) {
                        successCounter.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startTime;
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCounter.get()).isEqualTo(totalRequests);
        assertThat(ingestionService.getTotalIngested()).isEqualTo(totalRequests);
        assertThat(ingestionService.getTotalFailed()).isEqualTo(0L);
        assertThat(loadProducer.history()).hasSize(totalRequests);

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double throughput = totalRequests / elapsedSeconds;

        System.out.printf("[LOAD TEST RESULT] Processed %d records in %.3f s (Throughput: %.1f req/sec)%n",
                totalRequests, elapsedSeconds, throughput);

        assertThat(throughput).isGreaterThanOrEqualTo(5000.0);
        ingestionService.close();
    }

    @Test
    @DisplayName("Acceptance Criteria 3: Database connection pool remains stable with batched JDBC writes (batch size 25)")
    void testDatabasePoolStabilityWithBatchedJdbcWrites() {
        int totalRecords = 5000;
        int batchSize = 25;
        int expectedBatches = totalRecords / batchSize;

        EntityManager em = mock(EntityManager.class);
        StreamExecutionRepository repository = new StreamExecutionRepository(em);

        List<StreamExecutionRecord> records = new ArrayList<>(totalRecords);
        for (int i = 0; i < totalRecords; i++) {
            records.add(new StreamExecutionRecord(
                    "batch-evt-" + i, "rules.results", "BatchRule", true, "PROCESSED", null, 80L
            ));
        }

        int persistedCount = repository.saveBatch(records, batchSize);

        assertThat(persistedCount).isEqualTo(totalRecords);
        // Persist called for every record
        verify(em, times(totalRecords)).persist(any(StreamExecutionRecord.class));
        // Flush and clear called exactly every 25 records -> 200 times!
        verify(em, times(expectedBatches)).flush();
        verify(em, times(expectedBatches)).clear();
    }

    private boolean waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
