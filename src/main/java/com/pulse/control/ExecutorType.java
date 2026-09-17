package com.pulse.control;

/**
 * Execution model for rule evaluation: Loom Virtual Threads vs fixed Platform Thread Pool.
 */
public enum ExecutorType {
    VIRTUAL_THREADS,
    PLATFORM_POOL;

    public static ExecutorType fromString(String val) {
        if (val == null || val.isBlank()) {
            return defaultForCurrentJvm();
        }
        try {
            return ExecutorType.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultForCurrentJvm();
        }
    }

    public static ExecutorType defaultForCurrentJvm() {
        return Runtime.version().feature() >= 21 ? VIRTUAL_THREADS : PLATFORM_POOL;
    }
}
