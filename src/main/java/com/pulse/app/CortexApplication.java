package com.pulse.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Jakarta EE JAX-RS Application root for helix-cortex.
 * Exposes all REST endpoints under /api/v1.
 */
@ApplicationPath("/api/v1")
public class CortexApplication extends Application {
}
