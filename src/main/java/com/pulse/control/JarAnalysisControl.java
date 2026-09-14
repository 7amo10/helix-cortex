package com.pulse.control;

import com.pulse.entity.AnalysisClassMetric;
import com.pulse.entity.AnalysisStatus;
import com.pulse.entity.JarAnalysis;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Control bean orchestrating asynchronous bytecode analysis of uploaded JAR archives.
 * Leverages ASM for opcode instruction counting and antipattern detection,
 * firing asynchronous CDI events when performance antipatterns are discovered.
 */
@ApplicationScoped
public class JarAnalysisControl {

    private static final Logger log = LoggerFactory.getLogger(JarAnalysisControl.class);

    @PersistenceContext(unitName = "CortexPU")
    private EntityManager em;

    @Resource(lookup = "java:comp/DefaultManagedExecutorService")
    private ManagedExecutorService executor;

    @Inject
    private Event<RuleAntipatternEvent> antipatternBus;

    public JarAnalysisControl() {
    }

    public JarAnalysisControl(EntityManager em, Executor executor, Event<RuleAntipatternEvent> antipatternBus) {
        this.em = em;
        if (executor instanceof ManagedExecutorService mes) {
            this.executor = mes;
        }
        this.customExecutor = executor;
        this.antipatternBus = antipatternBus;
    }

    private Executor customExecutor;

    private Executor getEffectiveExecutor() {
        if (customExecutor != null) {
            return customExecutor;
        }
        if (executor != null) {
            return executor;
        }
        return ForkJoinPool.commonPool();
    }

    /**
     * Persists initial PENDING analysis record and schedules asynchronous bytecode analysis.
     *
     * @param jarStream   input stream of the JAR archive
     * @param filename    uploaded filename
     * @param engineerId  submitting engineer identifier
     * @return analysis job ID
     * @throws IOException on I/O read errors
     */
    @Transactional
    public Long submitAsync(InputStream jarStream, String filename, String engineerId) throws IOException {
        byte[] jarBytes = jarStream.readAllBytes();

        JarAnalysis analysis = new JarAnalysis(filename != null ? filename : "unknown.jar", engineerId);
        analysis.setStatus(AnalysisStatus.PENDING);
        analysis.setSubmittedAt(Instant.now());
        em.persist(analysis);
        em.flush();

        Long analysisId = analysis.getId();

        CompletableFuture.runAsync(
                () -> analyze(new ByteArrayInputStream(jarBytes), analysisId),
                getEffectiveExecutor()
        );

        return analysisId;
    }

    /**
     * Executes bytecode inspection on classes within the JAR archive.
     *
     * @param jarStream  JAR input stream
     * @param analysisId target analysis ID
     */
    public void analyze(InputStream jarStream, Long analysisId) {
        try (JarInputStream jis = new JarInputStream(jarStream)) {
            int classCount = 0;
            long totalOpcodes = 0;
            int antipatternCount = 0;
            List<AnalysisClassMetric> metrics = new ArrayList<>();
            List<String> classNames = new ArrayList<>();

            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }

                byte[] classBytes = readEntryBytes(jis);
                OpcodeCountingVisitor visitor = new OpcodeCountingVisitor();
                ClassReader cr = new ClassReader(classBytes);
                cr.accept(visitor, 0);

                String className = visitor.getClassName();
                if (className == null || className.isBlank()) {
                    className = entry.getName().replace(".class", "").replace('/', '.');
                }

                classCount++;
                totalOpcodes += visitor.getTotalOpcodes();
                classNames.add(className);

                boolean hasAntipattern = visitor.hasAnyAntipattern();
                AnalysisClassMetric metric = new AnalysisClassMetric(
                        className,
                        visitor.getTotalOpcodes(),
                        hasAntipattern,
                        null
                );
                metrics.add(metric);

                if (hasAntipattern) {
                    for (String ap : visitor.getDetectedAntipatterns()) {
                        antipatternCount++;
                        if (antipatternBus != null) {
                            antipatternBus.fireAsync(new RuleAntipatternEvent(
                                    className,
                                    ap,
                                    Instant.now(),
                                    analysisId
                            ));
                        }
                    }
                }
            }

            String depGraphJson = buildSimpleGraphJson(classNames);
            completeAnalysis(analysisId, metrics, classCount, totalOpcodes, antipatternCount, depGraphJson);

        } catch (Throwable t) {
            log.error("Failed bytecode analysis for analysis ID: {}", analysisId, t);
            failAnalysis(analysisId);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void completeAnalysis(Long analysisId, List<AnalysisClassMetric> metrics, int classCount,
                                 long totalOpcodes, int antipatternCount, String depGraphJson) {
        JarAnalysis analysis = em.find(JarAnalysis.class, analysisId);
        if (analysis != null) {
            analysis.setClassCount(classCount);
            analysis.setTotalOpcodes(totalOpcodes);
            analysis.setAntipatternCount(antipatternCount);
            analysis.setDependencyGraphJson(depGraphJson);
            analysis.setStatus(AnalysisStatus.COMPLETED);

            for (AnalysisClassMetric m : metrics) {
                analysis.addClassMetric(m);
            }
            em.merge(analysis);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void failAnalysis(Long analysisId) {
        try {
            JarAnalysis analysis = em.find(JarAnalysis.class, analysisId);
            if (analysis != null) {
                analysis.setStatus(AnalysisStatus.FAILED);
                em.merge(analysis);
            }
        } catch (Exception e) {
            log.error("Error setting analysis status to FAILED for ID: {}", analysisId, e);
        }
    }

    public Optional<JarAnalysis> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        JarAnalysis analysis = em.find(JarAnalysis.class, id);
        if (analysis != null && analysis.getClassMetrics() != null) {
            // trigger eager loading of metrics while in session
            analysis.getClassMetrics().size();
        }
        return Optional.ofNullable(analysis);
    }

    public List<JarAnalysis> findAll() {
        return em.createQuery("SELECT j FROM JarAnalysis j ORDER BY j.submittedAt DESC", JarAnalysis.class)
                .getResultList();
    }

    private byte[] readEntryBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private String buildSimpleGraphJson(List<String> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"classes\":[");
        for (int i = 0; i < classes.size(); i++) {
            sb.append("\"").append(classes.get(i)).append("\"");
            if (i < classes.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    public void setEntityManager(EntityManager em) {
        this.em = em;
    }

    public void setExecutor(Executor executor) {
        this.customExecutor = executor;
    }

    public void setAntipatternBus(Event<RuleAntipatternEvent> antipatternBus) {
        this.antipatternBus = antipatternBus;
    }
}
