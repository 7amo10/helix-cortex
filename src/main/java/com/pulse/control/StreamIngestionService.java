package com.pulse.control;

import com.helix.api.ExecutionContext;
import com.helix.api.stream.RuleEvent;
import com.helix.core.stream.kafka.KafkaRuleStreamProducer;
import com.helix.core.stream.kafka.KafkaStreamConfig;
import com.pulse.boundary.dto.BatchStreamIngestRequest;
import com.pulse.boundary.dto.BatchStreamIngestResponse;
import com.pulse.boundary.dto.StreamIngestRequest;
import com.pulse.boundary.dto.StreamIngestResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise service managing rapid REST event ingestion into Kafka topics.
 * Decouples high-throughput ingest requests from downstream evaluation and persistence.
 */
@ApplicationScoped
public class StreamIngestionService {

    private static final Logger log = LoggerFactory.getLogger(StreamIngestionService.class);

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.bootstrap-servers", defaultValue = "localhost:9092")
    private String bootstrapServers;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.input-topic", defaultValue = "rules.input")
    private String defaultInputTopic;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.enabled", defaultValue = "true")
    private boolean kafkaEnabled;

    private KafkaRuleStreamProducer producer;
    private final AtomicLong totalIngested = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    public StreamIngestionService() {
    }

    public StreamIngestionService(String bootstrapServers, String defaultInputTopic) {
        this.bootstrapServers = bootstrapServers;
        this.defaultInputTopic = defaultInputTopic;
        this.kafkaEnabled = true;
        init();
    }

    public StreamIngestionService(KafkaRuleStreamProducer producer, String defaultInputTopic) {
        this.producer = Objects.requireNonNull(producer, "producer cannot be null");
        this.defaultInputTopic = defaultInputTopic != null ? defaultInputTopic : "rules.input";
        this.kafkaEnabled = true;
    }

    public StreamIngestionService(Producer<String, byte[]> kafkaProducer, String defaultInputTopic) {
        this.producer = new KafkaRuleStreamProducer(Objects.requireNonNull(kafkaProducer, "kafkaProducer cannot be null"));
        this.defaultInputTopic = defaultInputTopic != null ? defaultInputTopic : "rules.input";
        this.kafkaEnabled = true;
    }

    @PostConstruct
    public void init() {
        if (!kafkaEnabled) {
            log.info("Kafka streaming ingestion service disabled by configuration");
            return;
        }

        if (this.producer == null) {
            try {
                KafkaStreamConfig config = KafkaStreamConfig.builder()
                        .bootstrapServers(bootstrapServers)
                        .groupId("helix-cortex-producer")
                        .build();
                this.producer = new KafkaRuleStreamProducer(config);
                log.info("StreamIngestionService initialized Kafka producer connecting to {}", bootstrapServers);
            } catch (Exception e) {
                log.warn("Could not immediately connect Kafka producer at {}: {}", bootstrapServers, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            try {
                producer.flush();
                producer.close();
                log.info("StreamIngestionService producer closed cleanly");
            } catch (Exception e) {
                log.warn("Error closing Kafka producer: {}", e.getMessage());
            }
        }
    }

    /**
     * Ingests a single event into the streaming pipeline.
     *
     * @param request event payload
     * @return immediate acknowledgment response
     */
    public StreamIngestResponse ingest(StreamIngestRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        String eventId = (request.eventId() != null && !request.eventId().isBlank())
                ? request.eventId()
                : UUID.randomUUID().toString();

        String topic = (request.topic() != null && !request.topic().isBlank())
                ? request.topic()
                : (defaultInputTopic != null ? defaultInputTopic : "rules.input");

        ExecutionContext context = new ExecutionContext(
                request.variables() != null ? request.variables() : Map.of()
        );

        RuleEvent event = RuleEvent.builder()
                .eventId(eventId)
                .topic(topic)
                .ruleName(request.ruleName())
                .context(context)
                .headers(request.headers())
                .timestamp(System.currentTimeMillis())
                .build();

        if (producer != null) {
            CompletableFuture<RecordMetadata> future = producer.publishEvent(topic, event);
            future.whenComplete((meta, err) -> {
                if (err != null) {
                    totalFailed.incrementAndGet();
                    log.error("Failed to publish event {} to Kafka topic {}: {}", eventId, topic, err.getMessage());
                }
            });
        }

        totalIngested.incrementAndGet();
        return new StreamIngestResponse(eventId, topic, request.ruleName(), "ACCEPTED", System.currentTimeMillis());
    }

    /**
     * Ingests a batch of events with high throughput.
     *
     * @param batchRequest batch of ingest requests
     * @return batch acknowledgment
     */
    public BatchStreamIngestResponse ingestBatch(BatchStreamIngestRequest batchRequest) {
        if (batchRequest == null || batchRequest.events() == null || batchRequest.events().isEmpty()) {
            return new BatchStreamIngestResponse(0, 0, List.of());
        }

        List<StreamIngestResponse> responses = new ArrayList<>(batchRequest.events().size());
        for (StreamIngestRequest req : batchRequest.events()) {
            responses.add(ingest(req));
        }

        if (producer != null) {
            producer.flush();
        }

        return new BatchStreamIngestResponse(batchRequest.events().size(), responses.size(), responses);
    }

    public void flush() {
        if (producer != null) {
            producer.flush();
        }
    }

    public long getTotalIngested() {
        return totalIngested.get();
    }

    public long getTotalFailed() {
        return totalFailed.get();
    }

    public String getDefaultInputTopic() {
        return defaultInputTopic;
    }

    public boolean isProducerActive() {
        return producer != null;
    }

    public void setProducer(KafkaRuleStreamProducer producer) {
        this.producer = producer;
    }

    public void setProducer(Producer<String, byte[]> kafkaProducer) {
        this.producer = new KafkaRuleStreamProducer(kafkaProducer);
    }
}
