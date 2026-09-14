package com.pulse.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/**
 * Jakarta EE JAX-RS Application root for helix-cortex.
 * Exposes all REST endpoints under /api/v1 with MicroProfile OpenAPI specification.
 */
@ApplicationPath("/api/v1")
@OpenAPIDefinition(
        info = @Info(
                title = "helix-cortex API",
                version = "1.0.0",
                description = "Enterprise Jakarta EE 10 API & Observability platform wrapping the helix-jvm-engine core."
        ),
        security = @SecurityRequirement(name = "BearerAuth"),
        components = @Components(
                securitySchemes = {
                        @SecurityScheme(
                                securitySchemeName = "BearerAuth",
                                type = SecuritySchemeType.HTTP,
                                scheme = "bearer",
                                bearerFormat = "JWT",
                                description = "HMAC-SHA256 signed JWT bearer token"
                        )
                }
        )
)
public class CortexApplication extends Application {
}
