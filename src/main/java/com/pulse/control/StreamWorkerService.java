package com.pulse.control;

import com.helix.api.CompiledRule;
import com.helix.core.stream.kafka.CommitMode;
import com.helix.core.stream.kafka.KafkaStreamConfig;
import com.helix.core.stream.kafka.KafkaStreamEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Worker service orchestrating clustered stream evaluation via {@link KafkaStreamEngine}.
 * Consumes events from the input topic, evaluates dynamic compiled rules,
 * and publishes structured results to the Kafka results topic.
 */
@ApplicationScoped
public class StreamWorkerService {

    private static final Logger log = LoggerFactory.getLogger(StreamWorkerService.class);

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.bootstrap-servers", defaultValue = "localhost:9092")
    private String bootstrapServers;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.group-id", defaultValue = "helix-cortex-stream-worker")
    private String groupId;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.input-topic", defaultValue = "rules.input")
    private String inputTopic;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.results-topic", defaultValue = "rules.results")
    private String resultsTopic;

    @Inject
    @ConfigProperty(name = "helix.cortex.kafka.enabled", defaultValue = "true")
    private boolean kafkaEnabled;

    @Inject
    private RuleCompilerService compilerService;

    private KafkaStreamEngine streamEngine;
    private Consumer<String, byte[]> customConsumer;
    private Producer<String, byte[]> customProducer;

    public StreamWorkerService() {
    }

    public StreamWorkerService(Consumer<String, byte[]> consumer, Producer<String, byte[]> producer,
                               String inputTopic, String resultsTopic) {
        this.customConsumer = Objects.requireNonNull(consumer, "consumer cannot be null");
        this.customProducer = Objects.requireNonNull(producer, "producer cannot be null");
        this.inputTopic = inputTopic != null ? inputTopic : "rules.input";
        this.resultsTopic = resultsTopic != null ? resultsTopic : "rules.results";
        this.kafkaEnabled = true;
        init();
    }

    @PostConstruct
    public void init() {
        if (!kafkaEnabled) {
            log.info("StreamWorkerService disabled by configuration");
            return;
        }

        KafkaStreamConfig config = KafkaStreamConfig.builder()
                .bootstrapServers(bootstrapServers != null ? bootstrapServers : "localhost:9092")
                .groupId(groupId != null ? groupId : "helix-cortex-stream-worker")
                .inputTopics(Set.of(inputTopic != null ? inputTopic : "rules.input"))
                .outputTopic(resultsTopic != null ? resultsTopic : "rules.results")
                .commitMode(CommitMode.SYNC)
                .pollTimeout(Duration.ofMillis(50))
                .build();

        try {
            if (customConsumer != null && customProducer != null) {
                this.streamEngine = new KafkaStreamEngine(config, customConsumer, customProducer);
            } else {
                this.streamEngine = new KafkaStreamEngine(config);
            }
            log.info("StreamWorkerService initialized with inputTopic='{}' and outputTopic='{}'",
                    config.getInputTopics(), config.getOutputTopic());
        } catch (Exception e) {
            log.warn("Could not immediately initialize KafkaStreamEngine at {}: {}", bootstrapServers, e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (streamEngine != null) {
            try {
                streamEngine.shutdown(Duration.ofSeconds(2));
                log.info("StreamWorkerService shut down cleanly");
            } catch (Exception e) {
                log.warn("Error shutting down stream engine: {}", e.getMessage());
            }
        }
    }

    public void registerRule(String topicOrRuleName, CompiledRule rule) {
        if (streamEngine != null) {
            streamEngine.registerRule(topicOrRuleName, rule);
        }
    }

    public KafkaStreamEngine getStreamEngine() {
        return streamEngine;
    }

    public boolean isRunning() {
        return streamEngine != null && streamEngine.isRunning();
    }
}
