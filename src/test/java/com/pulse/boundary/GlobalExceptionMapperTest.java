package com.pulse.boundary;

import com.helix.api.RuleCompilationException;
import com.helix.api.RuleExecutionException;
import com.pulse.boundary.dto.ProblemDetail;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionMapperTest {

    private GlobalExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GlobalExceptionMapper();
    }

    @Test
    @DisplayName("GlobalExceptionMapper must be annotated with @Provider")
    void testProviderAnnotation() {
        assertThat(GlobalExceptionMapper.class.getAnnotation(Provider.class)).isNotNull();
    }

    @Test
    @DisplayName("RuleCompilationException maps to 400 Bad Request with problem+json")
    void testRuleCompilationException() {
        RuleCompilationException ex = new RuleCompilationException("Invalid syntax in rule expression");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.title()).isEqualTo("Bad Request");
        assertThat(problem.detail()).contains("Invalid syntax");
    }

    @Test
    @DisplayName("RuleExecutionException maps to 422 Unprocessable Entity with problem+json")
    void testRuleExecutionException() {
        RuleExecutionException ex = new RuleExecutionException("Division by zero in rule calculation");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(422);
        assertThat(problem.title()).isEqualTo("Unprocessable Entity");
        assertThat(problem.detail()).contains("Division by zero");
    }

    @Test
    @DisplayName("WebApplicationException maps to its original HTTP status code")
    void testWebApplicationException() {
        NotFoundException ex = new NotFoundException("Endpoint does not exist");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(404);
        assertThat(problem.title()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("EntityNotFoundException maps to 404 Not Found")
    void testEntityNotFoundException() {
        EntityNotFoundException ex = new EntityNotFoundException("Session with id 42 not found");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(404);
        assertThat(problem.title()).isEqualTo("Not Found");
        assertThat(problem.detail()).contains("Session with id 42");
    }

    @Test
    @DisplayName("OptimisticLockException maps to 409 Conflict")
    void testOptimisticLockException() {
        OptimisticLockException ex = new OptimisticLockException("Concurrent modification detected");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.title()).isEqualTo("Conflict");
        assertThat(problem.detail()).contains("Concurrent modification");
    }

    @Test
    @DisplayName("Generic Exception maps to 500 Internal Server Error")
    void testGenericException() {
        RuntimeException ex = new RuntimeException("Unexpected runtime failure");
        Response response = mapper.toResponse(ex);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");

        ProblemDetail problem = (ProblemDetail) response.getEntity();
        assertThat(problem.status()).isEqualTo(500);
        assertThat(problem.title()).isEqualTo("Internal Server Error");
    }
}
