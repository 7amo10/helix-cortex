package com.pulse.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Publishes machine-learning model activation notifications across the Helix Cortex cluster
 * via Redis Pub/Sub topic {@code helix:models:activate}.
 *
 * <p>Notifies worker nodes in real time to reload and hot-swap in-process ONNX sessions
 * with zero downtime upon model version activation.</p>
 */
@ApplicationScoped
public class ModelActivationBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ModelActivationBroadcaster.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @ConfigProperty(name = "helix.models.redis.topic", defaultValue = "helix:models:activate")
    private String topic;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.host", defaultValue = "localhost")
    private String redisHost;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.port", defaultValue = "6379")
    private int redisPort;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.timeout", defaultValue = "2000")
    private int redisTimeout;

    private JedisPool jedisPool;
    private boolean ownsPool;

    public ModelActivationBroadcaster() {
        this.ownsPool = true;
    }

    public ModelActivationBroadcaster(JedisPool jedisPool, String topic) {
        this.jedisPool = jedisPool;
        this.topic = (topic != null && !topic.isBlank()) ? topic : "helix:models:activate";
        this.ownsPool = false;
    }

    @PostConstruct
    public void init() {
        if (this.jedisPool == null) {
            try {
                JedisPoolConfig poolConfig = new JedisPoolConfig();
                poolConfig.setMaxTotal(16);
                this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, redisTimeout);
                this.ownsPool = true;
                log.info("ModelActivationBroadcaster connected to Redis at {}:{} on topic '{}'",
                        redisHost, redisPort, topic);
            } catch (Exception e) {
                log.warn("Could not initialize Redis pool for ModelActivationBroadcaster: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (ownsPool && jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                log.warn("Error closing JedisPool in ModelActivationBroadcaster: {}", e.getMessage());
            }
        }
    }

    /**
     * Publishes a model activation notification payload to the configured Redis Pub/Sub topic.
     *
     * @param modelName     name of the activated model
     * @param activeVersion version string activated
     * @param storagePath   physical path to the .onnx binary file
     * @return true if published successfully, false otherwise
     */
    public boolean broadcastActivation(String modelName, String activeVersion, String storagePath) {
        if (modelName == null || modelName.isBlank() ||
                activeVersion == null || activeVersion.isBlank() ||
                storagePath == null || storagePath.isBlank()) {
            log.warn("Cannot broadcast model activation with null or blank arguments: modelName={}, version={}, path={}",
                    modelName, activeVersion, storagePath);
            return false;
        }

        if (jedisPool == null) {
            log.debug("Redis pool unavailable; skipping activation broadcast for model: {}", modelName);
            return false;
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("modelName", modelName.trim());
            payload.put("activeVersion", activeVersion.trim());
            payload.put("storagePath", storagePath.trim());
            payload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(payload);

            try (Jedis jedis = jedisPool.getResource()) {
                long clients = jedis.publish(topic, jsonPayload);
                log.info("Broadcasted model activation for '{}' v{} on topic '{}' (received by {} subscribers)",
                        modelName, activeVersion, topic, clients);
                return true;
            }
        } catch (JedisException e) {
            log.warn("Redis error broadcasting activation for model '{}' v{}: {}", modelName, activeVersion, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error constructing activation payload for model '{}': {}", modelName, e.getMessage());
            return false;
        }
    }

    public String getTopic() {
        return topic;
    }
}
