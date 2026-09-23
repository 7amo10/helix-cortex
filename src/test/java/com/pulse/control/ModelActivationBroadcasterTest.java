package com.pulse.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelActivationBroadcasterTest {

    @Mock
    private JedisPool jedisPool;

    @Mock
    private Jedis jedis;

    private ModelActivationBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        broadcaster = new ModelActivationBroadcaster(jedisPool, "helix:models:activate");
    }

    @Test
    @DisplayName("broadcastActivation publishes valid JSON event to configured Redis topic")
    void testBroadcastActivationSuccess() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.publish(anyString(), anyString())).thenReturn(1L);

        boolean success = broadcaster.broadcastActivation(
                "fraud_model_v1",
                "2.1.0",
                "/var/helix/models/fraud_model_v1/2.1.0/model.onnx"
        );

        assertThat(success).isTrue();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        verify(jedis).publish(topicCaptor.capture(), payloadCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("helix:models:activate");

        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("modelName").asText()).isEqualTo("fraud_model_v1");
        assertThat(payload.get("activeVersion").asText()).isEqualTo("2.1.0");
        assertThat(payload.get("storagePath").asText()).isEqualTo("/var/helix/models/fraud_model_v1/2.1.0/model.onnx");
        assertThat(payload.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("broadcastActivation handles null and blank arguments gracefully")
    void testBroadcastActivationInvalidArguments() {
        assertThat(broadcaster.broadcastActivation(null, "1.0", "/path")).isFalse();
        assertThat(broadcaster.broadcastActivation("model", null, "/path")).isFalse();
        assertThat(broadcaster.broadcastActivation("model", "1.0", null)).isFalse();
        assertThat(broadcaster.broadcastActivation("", "1.0", "/path")).isFalse();
        assertThat(broadcaster.broadcastActivation("model", "   ", "/path")).isFalse();
        assertThat(broadcaster.broadcastActivation("model", "1.0", "  ")).isFalse();

        verifyNoInteractions(jedisPool);
    }

    @Test
    @DisplayName("broadcastActivation handles JedisConnectionException gracefully without throwing")
    void testBroadcastActivationConnectionFailure() {
        when(jedisPool.getResource()).thenThrow(new JedisConnectionException("Redis unreachable"));

        boolean success = broadcaster.broadcastActivation(
                "fraud_model_v1",
                "1.0.0",
                "/path/model.onnx"
        );

        assertThat(success).isFalse();
    }

    @Test
    @DisplayName("broadcastActivation handles null JedisPool safely (standalone fallback mode)")
    void testBroadcastActivationNullPool() {
        ModelActivationBroadcaster standaloneBroadcaster = new ModelActivationBroadcaster(null, "helix:models:activate");

        boolean success = standaloneBroadcaster.broadcastActivation(
                "fraud_model_v1",
                "1.0.0",
                "/path/model.onnx"
        );

        assertThat(success).isFalse();
    }
}
