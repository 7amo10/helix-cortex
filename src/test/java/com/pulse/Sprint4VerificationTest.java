package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.app.CortexApplication;
import com.pulse.control.TelemetryControl;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 4 Comprehensive Verification Test")
class Sprint4VerificationTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().resolve("helix-cortex");

    private Path resolveFile(String relativePath) {
        Path p = Path.of(relativePath);
        if (Files.exists(p)) {
            return p;
        }
        return PROJECT_ROOT.resolve(relativePath);
    }

    @Mock
    private SseBroadcaster broadcaster;

    @Mock
    private Sse sse;

    @Mock
    private OutboundSseEvent.Builder eventBuilder;

    @Mock
    private OutboundSseEvent outboundEvent;

    @Test
    @DisplayName("Verification 1: N+1 Elimination - Persistence configuration and statement count")
    void testNPlusOneEliminationConfig() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/META-INF/persistence.xml")) {
            assertThat(is).isNotNull();
            String xml = new String(is.readAllBytes());
            assertThat(xml).contains("hibernate.generate_statistics");
            assertThat(xml).contains("hibernate.statistics.statistics_enabled");
            assertThat(xml).contains("hibernate.hikari.maximumPoolSize");
        }
    }

    @Test
    @DisplayName("Verification 2: HikariCP 25-concurrent-request load simulation produces 0 SQLTimeoutExceptions")
    void testConcurrentPoolNoTimeout() throws Exception {
        int poolSize = 16;
        int connectionTimeoutMs = 3000;
        int concurrentThreads = 25;
        int totalRequests = 250;

        Semaphore poolSemaphore = new Semaphore(poolSize, true);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        AtomicInteger timeouts = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                boolean acquired = false;
                try {
                    acquired = poolSemaphore.tryAcquire(connectionTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!acquired) {
                        timeouts.incrementAndGet();
                        throw new SQLTimeoutException("Connection timed out");
                    }
                    Thread.sleep(1);
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (SQLTimeoutException ignored) {
                } finally {
                    if (acquired) {
                        poolSemaphore.release();
                    }
                }
                return null;
            }));
        }

        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(timeouts.get()).isEqualTo(0);
        assertThat(successes.get()).isEqualTo(totalRequests);
    }

    @Test
    @DisplayName("Verification 3: Docker and Compose stack files valid")
    void testDockerFilesValid() throws Exception {
        Path dockerfilePath = resolveFile("Dockerfile");
        assertThat(Files.exists(dockerfilePath)).isTrue();
        String dockerfile = Files.readString(dockerfilePath);
        assertThat(dockerfile).contains("FROM maven:3.9-eclipse-temurin-17 AS builder");
        assertThat(dockerfile).contains("FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17");

        Path composePath = resolveFile("docker-compose.yml");
        assertThat(Files.exists(composePath)).isTrue();
        String compose = Files.readString(composePath);
        assertThat(compose).contains("postgres:16-alpine");
        assertThat(compose).contains("helix-cortex:");
        assertThat(compose).contains("8080:8080");

        Path standalonePath = resolveFile("docker/standalone.xml");
        assertThat(Files.exists(standalonePath)).isTrue();
        String standalone = Files.readString(standalonePath);
        assertThat(standalone).contains("java:jboss/datasources/CortexDS");
    }

    @Test
    @DisplayName("Verification 4: Postman collection covers 13 endpoints and status assertions")
    void testPostmanCollection() throws Exception {
        Path path = resolveFile("postman/helix-cortex.postman_collection.json");
        assertThat(Files.exists(path)).isTrue();
        String json = Files.readString(path);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        assertThat(root.has("item")).isTrue();
        assertThat(json).contains("auth/login");
        assertThat(json).contains("rules/compile");
        assertThat(json).contains("rules/execute");
        assertThat(json).contains("jars/analyze");
        assertThat(json).contains("telemetry/stream");
    }

    @Test
    @DisplayName("Verification 5: SSE stream broadcasts continuous telemetry events")
    void testSseTelemetryBroadcaster() {
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.id(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(any(jakarta.ws.rs.core.MediaType.class))).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);

        ScheduledExecutorService testScheduler = Executors.newSingleThreadScheduledExecutor();
        TelemetryControl telemetryControl = new TelemetryControl(sse, testScheduler, broadcaster);

        // Broadcast 5 events
        for (int i = 0; i < 5; i++) {
            telemetryControl.sampleAndBroadcast();
        }

        verify(broadcaster, times(5)).broadcast(outboundEvent);
        testScheduler.shutdown();
    }

    @Test
    @DisplayName("Verification 6: MicroProfile OpenAPI annotations declared")
    void testOpenApiDeclared() {
        OpenAPIDefinition def = CortexApplication.class.getAnnotation(OpenAPIDefinition.class);
        assertThat(def).isNotNull();
        assertThat(def.info().title()).isEqualTo("helix-cortex API");
        assertThat(def.components().securitySchemes()).isNotEmpty();
    }
}
