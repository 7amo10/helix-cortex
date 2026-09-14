package com.pulse.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous CDI observer processing bytecode antipattern events detected during JAR analysis.
 */
@ApplicationScoped
public class AntipatternObserver {

    private static final Logger log = LoggerFactory.getLogger(AntipatternObserver.class);

    @Inject
    private AuditLogRepository auditLogRepository;

    public AntipatternObserver() {
    }

    public AntipatternObserver(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Asynchronously handles RuleAntipatternEvent notifications.
     * Logs the alert and persists an audit entry.
     *
     * @param event antipattern event payload
     */
    public void onAntipattern(@ObservesAsync RuleAntipatternEvent event) {
        if (event == null) {
            return;
        }

        String alertMessage = String.format(
                "[ANTIPATTERN ALERT] Analysis ID: %s | Class: %s | Antipattern: %s | DetectedAt: %s",
                event.jarAnalysisId(),
                event.className(),
                event.antipatternType(),
                event.detectedAt()
        );

        System.err.println(alertMessage);
        log.warn("{}", alertMessage);

        if (auditLogRepository != null) {
            String endpoint = "antipattern/" + event.antipatternType() + "/" + event.className();
            auditLogRepository.logAsync("SYSTEM", endpoint, "EVENT", true);
        }
    }

    public void setAuditLogRepository(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }
}
