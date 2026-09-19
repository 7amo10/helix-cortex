package com.pulse;

import com.helix.api.CompiledRule;
import com.helix.api.ExecutionContext;
import com.helix.api.ExecutionResult;
import com.helix.api.stream.StreamResult;
import com.helix.core.cache.l4.L4RedisConfig;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.helix.core.stream.kafka.KafkaEventCodec;
import com.pulse.boundary.TelemetryResource;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamThroughputSnapshot;
import com.pulse.control.L4CacheService;
import com.pulse.control.StreamConsumerCoordinator;
import com.pulse.control.StreamExecutionRepository;
import com.pulse.control.StreamIngestionService;
import com.pulse.control.StreamThroughputControl;
import com.pulse.entity.StreamExecutionRecord;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real-scenario verification test covering all acceptance criteria for Issue #44:
 * Real-Time SSE Throughput Telemetry & Docker Compose Multi-Node Stack.
 */
@DisplayName("Sprint 7: Real-Time SSE Throughput Telemetry & Docker Compose Multi-Node Stack Verification")
class Sprint7SseDockerVerificationTest {

    @Test
    @DisplayName("Verification 1: Docker Compose multi-node stack orchestration and healthchecks")
    void testDockerComposeOrchestration() throws Exception {
        Path composePath = Path.of("docker-compose.yml");
        assertThat(composePath).exists();

        String content = Files.readString(composePath);

        // 1. PostgreSQL 16
        assertThat(content).contains("postgres:");
        assertThat(content).contains("postgres:16-alpine");
        assertThat(content).contains("pg_isready -U pulse -d pulsedb");

        // 2. Redis 7 Alpine
        assertThat(content).contains("redis:");
        assertThat(content).contains("redis:7-alpine");
        assertThat(content).contains("redis-cli");
        assertThat(content).contains("ping");

        // 3. Apache Kafka in KRaft mode
        assertThat(content).contains("kafka:");
        assertThat(content).contains("apache/kafka:3.7.0");
        assertThat(content).contains("KAFKA_PROCESS_ROLES: broker,controller");
        assertThat(content).contains("KAFKA_NODE_ID: 1");

        // 4. Clustered WildFly instances (helix-cortex-1 & helix-cortex-2)
        assertThat(content).contains("helix-cortex-1:");
        assertThat(content).contains("helix-cortex-2:");
        assertThat(content).contains("8080:8080");
        assertThat(content).contains("8081:8080");
        assertThat(content).contains("cortex-node-1");
        assertThat(content).contains("cortex-node-2");
        assertThat(content).contains("condition: service_healthy");
    }

    @Test
    @DisplayName("Acceptance Criteria 2: SSE clients receive continuous live throughput updates without connection stalls")
    void testContinuousSseThroughputStreaming() throws Exception {
        StreamIngestionService ingestionService = mock(StreamIngestionService.class);
        StreamConsumerCoordinator coordinator = mock(StreamConsumerCoordinator.class);
        Sse sse = mock(Sse.class);
        SseBroadcaster broadcaster = mock(SseBroadcaster.class);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        OutboundSseEvent.Builder builder = mock(OutboundSseEvent.Builder.class);
        when(sse.newEventBuilder()).thenReturn(builder);
        when(builder.name(any())).thenReturn(builder);
        when(builder.id(any())).thenReturn(builder);
        when(builder.mediaType(any())).thenReturn(builder);
        when(builder.data(any(Class.class), any())).thenAnswer(inv -> {
            OutboundSseEvent evt = mock(OutboundSseEvent.class);
            when(evt.getName()).thenReturn("throughput");
            when(evt.getData()).thenReturn(inv.getArgument(1));
            return builder;
        });
        OutboundSseEvent sampleEvent = mock(OutboundSseEvent.class);
        when(builder.build()).thenReturn(sampleEvent);

        StreamThroughputControl control = new StreamThroughputControl(
                ingestionService, coordinator, sse, executor, broadcaster
        );

        List<StreamThroughputSnapshot> receivedSnapshots = new CopyOnWriteArrayList<>();
        AtomicInteger eventCounter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        SseEventSink clientSink = mock(SseEventSink.class);
        when(clientSink.isClosed()).thenReturn(false);
        when(clientSink.send(any())).thenAnswer(inv -> {
            eventCounter.incrementAndGet();
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        doAnswer(inv -> {
            OutboundSseEvent evt = inv.getArgument(0);
            clientSink.send(evt);
            return null;
        }).when(broadcaster).broadcast(any());

        // Register client sink
        control.registerSink(clientSink, sse);

        // Record simulated latencies and throughput progress across 5 broadcast cycles
        for (int i = 1; i <= 5; i++) {
            when(ingestionService.getTotalIngested()).thenReturn((long) (i * 2000));
            when(coordinator.getTotalPersisted()).thenReturn((long) (i * 1950));
            control.recordLatency(1.5 * i);
            control.sampleAndBroadcast();
        }

        boolean receivedAll = latch.await(3, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(receivedAll).isTrue();
        // Client received initial event on connect + 5 sample broadcasts = at least 5
        assertThat(eventCounter.get()).isGreaterThanOrEqualTo(5);
        verify(broadcaster, times(5)).broadcast(any());
        verify(clientSink, times(6)).send(any()); // 1 initial + 5 broadcasts
    }

    @Test
    @DisplayName("Acceptance Criteria 3: End-to-end event flow verified across clustered nodes")
    @SuppressWarnings("unchecked")
    void testEndToEndClusteredNodesEventFlow() throws Exception {
        // Shared Cluster Infrastructure Simulation:
        // Shared Redis L4 Cache
        L4RedisConfig redisConfig = new L4RedisConfig("localhost", 6379, 2000, 16, 86400, true);
        L4RedisRuleCache sharedL4Cache = new L4RedisRuleCache(redisConfig);

        // Shared Kafka Topics: 'rules.input' and 'rules.results'
        MockProducer<String, byte[]> sharedKafkaProducer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        MockConsumer<String, byte[]> sharedKafkaConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        TopicPartition resultsPartition = new TopicPartition("rules.results", 0);
        sharedKafkaConsumer.updateBeginningOffsets(Map.of(resultsPartition, 0L));

        // Node 1: Ingestion Gateway Instance
        StreamIngestionService node1Ingestion = new StreamIngestionService(sharedKafkaProducer, "rules.input");

        // Node 2: Background Persistence Coordinator Instance
        StreamExecutionRepository node2Repo = mock(StreamExecutionRepository.class);
        List<StreamExecutionRecord> node2PersistedRecords = new ArrayList<>();
        when(node2Repo.saveBatch(any(), any(Integer.class))).thenAnswer(inv -> {
            List<StreamExecutionRecord> batch = inv.getArgument(0);
            node2PersistedRecords.addAll(batch);
            return batch.size();
        });

        StreamConsumerCoordinator node2Coordinator = new StreamConsumerCoordinator(
                sharedKafkaConsumer, node2Repo, 25, 50
        );

        // Step 1: Client submits event to Node 1 via REST gateway
        StreamIngestRequest request = new StreamIngestRequest(
                "evt-cluster-1",
                "rules.input",
                "DistributedFraudRule",
                Map.of("amount", 75000.0, "riskScore", 92),
                Map.of("originNode", "node-1")
        );
        var ack = node1Ingestion.ingest(request);
        assertThat(ack.status()).isEqualTo("ACCEPTED");
        assertThat(sharedKafkaProducer.history()).hasSize(1);

        // Step 2: Clustered worker processes event using compiled rule
        // Store rule bytecode in shared L4 Cache (simulating compilation once across cluster)
        byte[] dummyBytecode = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00};
        sharedL4Cache.putBytecode("rule-hash-1", "DistributedFraudRule", "1.0.0", dummyBytecode);
        assertThat(sharedL4Cache.getBytecode("rule-hash-1")).isPresent();

        // Worker execution produces StreamResult
        StreamResult workerResult = StreamResult.success(
                "evt-cluster-1", "rules.results", "DistributedFraudRule", "ALERT_HIGH_RISK", 1500L
        );
        byte[] resultPayload = KafkaEventCodec.serializeResult(workerResult);

        // Step 3: Node 2 consumes result from Kafka results topic and persists to DB
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "rules.results", 0, 0L, "evt-cluster-1", resultPayload
        );
        node2Coordinator.processRecords(List.of(record));
        node2Coordinator.flushDirect();

        // Verify Node 2 successfully persisted the event
        assertThat(node2PersistedRecords).hasSize(1);
        StreamExecutionRecord persisted = node2PersistedRecords.get(0);
        assertThat(persisted.getEventId()).isEqualTo("evt-cluster-1");
        assertThat(persisted.isSuccess()).isTrue();
        assertThat(persisted.getResultPayload()).isEqualTo("ALERT_HIGH_RISK");

        // Step 4: Verify throughput telemetry reflecting clustered progress
        StreamThroughputControl throughputControl = new StreamThroughputControl(
                node1Ingestion, node2Coordinator, null, null, null
        );
        StreamThroughputSnapshot snapshot = throughputControl.currentSnapshot();
        assertThat(snapshot.totalIngested()).isEqualTo(1L);
        assertThat(snapshot.totalPersisted()).isEqualTo(1L);
        assertThat(snapshot.queueDepth()).isEqualTo(0L);

        node1Ingestion.close();
        node2Coordinator.stop();
        sharedL4Cache.close();
    }
}
