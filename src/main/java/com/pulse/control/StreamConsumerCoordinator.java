package com.pulse.control;

import com.helix.api.stream.StreamResult;
import com.helix.core.stream.kafka.KafkaEventCodec;
import com.helix.core.stream.kafka.KafkaStreamConfig;
import com.pulse.boundary.dto.StreamCoordinatorStats;
import com.pulse.entity.StreamExecutionRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton startup coordinator consuming evaluation results from the Kafka results topic
 * and batch-persisting them into PostgreSQL via {@link StreamExecutionRepository} and HikariCP.
 */
@Singleton
@Startup
public class StreamConsumerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumerCoordinator.class);

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.bootstrap-servers", defaultValue = "localhost:9092")
    private String bootstrapServers;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.group-id", defaultValue = "helix-cortex-stream-coordinator")
    private String groupId;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.input-topic", defaultValue = "rules.input")
    private String inputTopic;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.results-topic", defaultValue = "rules.results")
    private String resultsTopic;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.batch-size", defaultValue = "25")
    private int batchSize;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.flush-interval-ms", defaultValue = "50")
    private long flushIntervalMs;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.consumer.auto-offset-reset", defaultValue = "earliest")
    private String autoOffsetReset;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.enabled", defaultValue = "true")
    private boolean kafkaEnabled;

    @Inject
    private StreamExecutionRepository repository;

    private Consumer<String, byte[]> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong totalPersisted = new AtomicLong(0);
    private final AtomicLong batchesPersisted = new AtomicLong(0);

    private final List<StreamExecutionRecord> buffer = new ArrayList<>();
    private long lastFlushNanos = System.nanoTime();
    private Thread pollingThread;

    public StreamConsumerCoordinator() {
    }

    public StreamConsumerCoordinator(Consumer<String, byte[]> consumer, StreamExecutionRepository repository,
                                     int batchSize, long flushIntervalMs) {
        this.consumer = consumer;
        this.repository = repository;
        this.batchSize = batchSize > 0 ? batchSize : 25;
        this.flushIntervalMs = flushIntervalMs > 0 ? flushIntervalMs : 50;
        this.resultsTopic = "rules.results";
        this.inputTopic = "rules.input";
        this.kafkaEnabled = true;
    }

    @PostConstruct
    public void start() {
        if (!kafkaEnabled) {
            log.info("StreamConsumerCoordinator disabled by configuration");
            return;
        }

        if (this.consumer == null) {
            try {
                KafkaStreamConfig config = KafkaStreamConfig.builder()
                        .bootstrapServers(bootstrapServers)
                        .groupId(groupId)
                        .autoOffsetReset(autoOffsetReset)
                        .enableAutoCommit(false)
                        .build();
                this.consumer = new KafkaConsumer<>(config.toConsumerProperties());
                log.info("StreamConsumerCoordinator initialized Kafka consumer connecting to {}", bootstrapServers);
            } catch (Exception e) {
                log.warn("Could not immediately connect Kafka consumer at {}: {}", bootstrapServers, e.getMessage());
                return;
            }
        }

        try {
            consumer.subscribe(Collections.singletonList(resultsTopic));
            running.set(true);
            lastFlushNanos = System.nanoTime();
            pollingThread = Thread.ofVirtual().name("helix-stream-consumer-coordinator").start(this::pollLoop);
            log.info("StreamConsumerCoordinator started polling on topic '{}' with batch size {}", resultsTopic, batchSize);
        } catch (Exception e) {
            log.warn("Error starting Kafka subscription on topic {}: {}", resultsTopic, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping StreamConsumerCoordinator...");
            flushDirect();
            if (consumer != null) {
                try {
                    consumer.wakeup();
                } catch (Exception ignored) {
                }
                try {
                    consumer.close(Duration.ofSeconds(2));
                } catch (Exception e) {
                    log.warn("Error closing Kafka consumer: {}", e.getMessage());
                }
            }
            log.info("StreamConsumerCoordinator stopped cleanly");
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (!records.isEmpty()) {
                    processRecords(records);
                }
                checkPeriodicFlush();
            } catch (WakeupException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Exception during Kafka poll loop: {}", e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Converts received Kafka consumer records to StreamExecutionRecord entities and buffers them.
     */
    public void processRecords(Iterable<ConsumerRecord<String, byte[]>> records) {
        synchronized (buffer) {
            for (ConsumerRecord<String, byte[]> record : records) {
                totalConsumed.incrementAndGet();
                StreamResult result = KafkaEventCodec.deserializeResult(record.value());

                String eventId = (result.getEventId() != null && !result.getEventId().isBlank())
                        ? result.getEventId()
                        : record.key();
                if (eventId == null || eventId.isBlank()) {
                    eventId = "evt-" + System.currentTimeMillis() + "-" + record.offset();
                }

                String topic = (result.getTopic() != null && !result.getTopic().isBlank())
                        ? result.getTopic()
                        : record.topic();

                String resultPayload = result.getResultRaw() != null ? String.valueOf(result.getResultRaw()) : null;
                String errorMessage = result.getError().map(Throwable::getMessage).orElse(null);

                StreamExecutionRecord entity = new StreamExecutionRecord(
                        eventId,
                        topic,
                        result.getRuleName(),
                        result.isSuccess(),
                        resultPayload,
                        errorMessage,
                        result.getExecutionTimeNanos()
                );

                entity.addMetric("executionTimeNanos", (double) result.getExecutionTimeNanos(), String.valueOf(result.getExecutionTimeNanos()));
                entity.addMetric("kafka.partition", (double) record.partition(), String.valueOf(record.partition()));
                entity.addMetric("kafka.offset", (double) record.offset(), String.valueOf(record.offset()));
                entity.addMetric("status", result.isSuccess() ? 1.0 : 0.0, result.isSuccess() ? "SUCCESS" : "FAILURE");

                buffer.add(entity);
            }

            if (buffer.size() >= batchSize) {
                flushBufferInternal();
            }
        }
    }

    private void checkPeriodicFlush() {
        synchronized (buffer) {
            if (!buffer.isEmpty()) {
                long elapsedNanos = System.nanoTime() - lastFlushNanos;
                if (elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)) {
                    flushBufferInternal();
                }
            }
        }
    }

    public void flushDirect() {
        synchronized (buffer) {
            flushBufferInternal();
        }
    }

    private void flushBufferInternal() {
        if (buffer.isEmpty()) {
            return;
        }

        List<StreamExecutionRecord> toPersist = new ArrayList<>(buffer);
        buffer.clear();
        lastFlushNanos = System.nanoTime();

        try {
            if (repository != null) {
                int count = repository.saveBatch(toPersist, batchSize);
                totalPersisted.addAndGet(count);
                batchesPersisted.incrementAndGet();
                log.debug("Persisted batch of {} stream records (total persisted: {})", count, totalPersisted.get());
            }

            if (consumer != null && running.get()) {
                try {
                    consumer.commitSync();
                } catch (Exception e) {
                    log.debug("Offset commit warning (non-fatal): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to persist stream records batch: {}", e.getMessage(), e);
        }
    }

    public StreamCoordinatorStats getStats() {
        return new StreamCoordinatorStats(
                0L,
                totalConsumed.get(),
                totalPersisted.get(),
                batchesPersisted.get(),
                running.get(),
                inputTopic,
                resultsTopic
        );
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }

    public long getTotalPersisted() {
        return totalPersisted.get();
    }

    public long getBatchesPersisted() {
        return batchesPersisted.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setConsumer(Consumer<String, byte[]> consumer) {
        this.consumer = consumer;
    }

    public void setRepository(StreamExecutionRepository repository) {
        this.repository = repository;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
