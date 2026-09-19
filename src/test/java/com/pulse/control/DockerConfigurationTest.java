package com.pulse.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Task 4.3: Docker Compose and Dockerfile Acceptance Criteria Verification")
class DockerConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().resolve("helix-cortex");

    private Path resolveFile(String relativePath) {
        Path p = Path.of(relativePath);
        if (Files.exists(p)) {
            return p;
        }
        return PROJECT_ROOT.resolve(relativePath);
    }

    @Test
    @DisplayName("Acceptance Criteria 1: Multi-stage Dockerfile: Maven 3.9 Temurin 17 builder -> WildFly 31.0.0.Final runtime")
    void testDockerfileMultiStage() throws Exception {
        Path dockerfilePath = resolveFile("Dockerfile");
        assertThat(Files.exists(dockerfilePath)).isTrue();

        String content = Files.readString(dockerfilePath);
        assertThat(content).contains("FROM maven:3.9-eclipse-temurin-17 AS builder");
        assertThat(content).contains("FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17");
    }

    @Test
    @DisplayName("Acceptance Criteria 2: Runtime copies helix-cortex.war and docker/standalone.xml with CortexDS datasource")
    void testDockerfileCopiesWarAndConfig() throws Exception {
        Path dockerfilePath = resolveFile("Dockerfile");
        String content = Files.readString(dockerfilePath);

        assertThat(content).contains("helix-cortex.war $JBOSS_HOME/standalone/deployments/");
        assertThat(content).contains("docker/standalone.xml $JBOSS_HOME/standalone/configuration/");

        Path standalonePath = resolveFile("docker/standalone.xml");
        assertThat(Files.exists(standalonePath)).isTrue();
        String standaloneContent = Files.readString(standalonePath);
        assertThat(standaloneContent).contains("java:jboss/datasources/CortexDS");
        assertThat(standaloneContent).contains("${env.DB_URL");
        assertThat(standaloneContent).contains("${env.DB_USER");
        assertThat(standaloneContent).contains("${env.DB_PASSWORD");
    }

    @Test
    @DisplayName("Acceptance Criteria 3: docker-compose.yml defines postgres, redis, kafka, and clustered helix-cortex services with healthchecks")
    void testDockerComposeServices() throws Exception {
        Path composePath = resolveFile("docker-compose.yml");
        assertThat(Files.exists(composePath)).isTrue();

        String content = Files.readString(composePath);
        assertThat(content).contains("postgres:");
        assertThat(content).contains("postgres:16-alpine");
        assertThat(content).contains("healthcheck:");
        assertThat(content).contains("pg_isready");
        assertThat(content).contains("pulsedb");

        assertThat(content).contains("redis:");
        assertThat(content).contains("redis:7-alpine");

        assertThat(content).contains("kafka:");
        assertThat(content).contains("apache/kafka:3.7.0");

        assertThat(content).contains("helix-cortex-1:");
        assertThat(content).contains("helix-cortex-2:");
        assertThat(content).contains("condition: service_healthy");
        assertThat(content).contains("8080:8080");
        assertThat(content).contains("8081:8080");
    }

    @Test
    @DisplayName("Acceptance Criteria 4: .env.example documents JWT_SECRET, DB_PASSWORD")
    void testEnvExampleVariables() throws Exception {
        Path envExamplePath = resolveFile(".env.example");
        assertThat(Files.exists(envExamplePath)).isTrue();

        String content = Files.readString(envExamplePath);
        assertThat(content).contains("JWT_SECRET=");
        assertThat(content).contains("DB_PASSWORD=");
    }
}
