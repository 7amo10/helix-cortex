package com.pulse.boundary.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CapabilityViolationResponse DTO Specification")
class CapabilityViolationResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Convenience constructor sets HTTP 422 status and maps diagnostic fields")
    void testConvenienceConstructor() {
        CapabilityViolationResponse response = new CapabilityViolationResponse(
                "java.lang.System",
                "exit",
                "Direct JVM termination is restricted in sandbox"
        );

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.title()).isEqualTo("Unprocessable Entity");
        assertThat(response.violatingClass()).isEqualTo("java.lang.System");
        assertThat(response.getViolatingClass()).isEqualTo("java.lang.System");
        assertThat(response.violatingMethod()).isEqualTo("exit");
        assertThat(response.getViolatingMethod()).isEqualTo("exit");
        assertThat(response.errorMessage()).isEqualTo("Direct JVM termination is restricted in sandbox");
        assertThat(response.getErrorMessage()).isEqualTo("Direct JVM termination is restricted in sandbox");
        assertThat(response.detail()).isEqualTo("Direct JVM termination is restricted in sandbox");
    }

    @Test
    @DisplayName("Serialization to JSON preserves all fields for RFC 7807 and structured error compliance")
    void testJsonSerialization() throws Exception {
        CapabilityViolationResponse response = new CapabilityViolationResponse(
                "java.lang.Runtime",
                "exec",
                "Process execution forbidden in untrusted tenant sandbox"
        );

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"status\":422");
        assertThat(json).contains("\"title\":\"Unprocessable Entity\"");
        assertThat(json).contains("\"violatingClass\":\"java.lang.Runtime\"");
        assertThat(json).contains("\"violatingMethod\":\"exec\"");
        assertThat(json).contains("\"errorMessage\":\"Process execution forbidden in untrusted tenant sandbox\"");
    }
}
