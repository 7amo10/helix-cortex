package com.pulse.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CortexApplicationTest {

    @Test
    @DisplayName("CortexApplication should extend JAX-RS Application and configure /api/v1 path")
    void testApplicationConfiguration() {
        CortexApplication app = new CortexApplication();
        assertThat(app).isInstanceOf(Application.class);

        ApplicationPath annotation = CortexApplication.class.getAnnotation(ApplicationPath.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("/api/v1");
    }
}
