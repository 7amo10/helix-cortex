package com.pulse.control;

import com.helix.HelixApplication;
import com.helix.api.RuleEngine;
import com.helix.api.profiler.Profiler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI Producer providing application-scoped RuleEngine and Profiler instances
 * backed by the Helix JVM Scripting Engine runtime.
 */
@ApplicationScoped
public class HelixProducer {

    private static final Logger log = LoggerFactory.getLogger(HelixProducer.class);

    @Produces
    @ApplicationScoped
    public RuleEngine produceRuleEngine() {
        log.info("Producing application-scoped Helix RuleEngine instance");
        return HelixApplication.createEngine();
    }

    public void disposeRuleEngine(@Disposes RuleEngine ruleEngine) {
        log.info("Disposing Helix RuleEngine instance");
        if (ruleEngine instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Error closing RuleEngine on dispose: {}", e.getMessage());
            }
        }
    }

    @Produces
    @ApplicationScoped
    public Profiler produceProfiler(RuleEngine engine) {
        log.info("Producing application-scoped Helix Profiler instance");
        return HelixApplication.createProfiler(engine);
    }

    public void disposeProfiler(@Disposes Profiler profiler) {
        log.info("Disposing Helix Profiler instance and halting telemetry");
        if (profiler != null && profiler.isRunning()) {
            profiler.stop();
        }
    }
}
