package com.pulse;

import com.pulse.boundary.JarAnalysisResource;
import com.pulse.boundary.TelemetryResource;
import com.pulse.boundary.dto.JvmTelemetrySnapshot;
import com.pulse.control.*;
import com.pulse.entity.AnalysisStatus;
import com.pulse.entity.JarAnalysis;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Event;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Sprint3VerificationTest {

    @Mock
    private EntityManager em;

    @Mock
    private Event<RuleAntipatternEvent> antipatternBus;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Principal principal;

    @Mock
    private Sse sse;

    @Mock
    private SseBroadcaster sseBroadcaster;

    @Mock
    private SseEventSink sseEventSink;

    @Mock
    private OutboundSseEvent.Builder eventBuilder;

    @Mock
    private OutboundSseEvent outboundEvent;

    private JarAnalysisControl jarControl;
    private JarAnalysisResource jarResource;
    private AntipatternObserver antipatternObserver;
    private TelemetryControl telemetryControl;
    private TelemetryResource telemetryResource;

    private final List<JarAnalysis> sessionDatabase = new ArrayList<>();
    private long idSeq = 1L;

    @BeforeEach
    void setUp() {
        jarControl = new JarAnalysisControl(em, Runnable::run, antipatternBus);
        jarResource = new JarAnalysisResource(jarControl);
        jarResource.setSecurityContext(securityContext);

        antipatternObserver = new AntipatternObserver(auditLogRepository);

        telemetryControl = new TelemetryControl(sse, null, sseBroadcaster);
        telemetryResource = new TelemetryResource(telemetryControl);
    }

    private byte[] generateTestJar(boolean triggerAntipatterns) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            // Class 1: Calculator (clean bytecode)
            ClassWriter cw1 = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw1.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/calc/Calculator", null, "java/lang/Object", null);
            MethodVisitor mv1 = cw1.visitMethod(Opcodes.ACC_PUBLIC, "add", "(II)I", null, null);
            mv1.visitCode();
            mv1.visitVarInsn(Opcodes.ILOAD, 1);
            mv1.visitVarInsn(Opcodes.ILOAD, 2);
            mv1.visitInsn(Opcodes.IADD);
            mv1.visitInsn(Opcodes.IRETURN);
            mv1.visitMaxs(0, 0);
            mv1.visitEnd();
            cw1.visitEnd();

            jos.putNextEntry(new JarEntry("com/pulse/calc/Calculator.class"));
            jos.write(cw1.toByteArray());
            jos.closeEntry();

            if (triggerAntipatterns) {
                // Class 2: BadService (55 NEW opcodes -> EXCESSIVE_OBJECT_CREATION)
                ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                cw2.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/bad/BadService", null, "java/lang/Object", null);
                MethodVisitor mv2 = cw2.visitMethod(Opcodes.ACC_PUBLIC, "leak", "()V", null, null);
                mv2.visitCode();
                for (int i = 0; i < 55; i++) {
                    mv2.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
                    mv2.visitInsn(Opcodes.DUP);
                    mv2.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    mv2.visitInsn(Opcodes.POP);
                }
                mv2.visitInsn(Opcodes.RETURN);
                mv2.visitMaxs(0, 0);
                mv2.visitEnd();
                cw2.visitEnd();

                jos.putNextEntry(new JarEntry("com/pulse/bad/BadService.class"));
                jos.write(cw2.toByteArray());
                jos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Verification 1: POST /api/v1/jars/analyze returns 202 Accepted with Location header")
    void testUploadReturns202WithLocation() throws Exception {
        byte[] jar = generateTestJar(false);

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("engineer_1");

        doAnswer(inv -> {
            JarAnalysis ja = inv.getArgument(0);
            Field idField = JarAnalysis.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(ja, idSeq++);
            sessionDatabase.add(ja);
            return null;
        }).when(em).persist(any(JarAnalysis.class));

        when(em.find(eq(JarAnalysis.class), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(1);
            return sessionDatabase.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
        });

        Response response = jarResource.analyze(new ByteArrayInputStream(jar), "engine-core.jar", securityContext);

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        assertThat(response.getLocation()).isNotNull();
        assertThat(response.getLocation().toString()).startsWith("/api/v1/jars/sessions/");
    }

    @Test
    @DisplayName("Verification 2: Asynchronous analysis completes with classCount > 0 and totalOpcodes > 0")
    void testAsyncAnalysisLifecycle() throws Exception {
        byte[] jar = generateTestJar(false);
        JarAnalysis session = new JarAnalysis("engine-core.jar", "engineer_1");
        session.setId(10L);

        when(em.find(JarAnalysis.class, 10L)).thenReturn(session);

        jarControl.analyze(new ByteArrayInputStream(jar), 10L);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(session.getClassCount()).isEqualTo(1);
        assertThat(session.getTotalOpcodes()).isGreaterThan(0);
        assertThat(session.getClassMetrics()).hasSize(1);
        assertThat(session.getClassMetrics().get(0).getClassName()).isEqualTo("com.pulse.calc.Calculator");
        assertThat(session.getDependencyGraphJson()).contains("com.pulse.calc.Calculator");
    }

    @Test
    @DisplayName("Verification 3: Antipattern triggers async CDI event and logs alert into AuditLog")
    void testAntipatternDetectionAndCDIEvent() throws Exception {
        byte[] jarWithAntipattern = generateTestJar(true);
        JarAnalysis session = new JarAnalysis("risky.jar", "engineer_1");
        session.setId(20L);

        when(em.find(JarAnalysis.class, 20L)).thenReturn(session);

        jarControl.analyze(new ByteArrayInputStream(jarWithAntipattern), 20L);

        // Verify antipattern was detected on BadService and fired asynchronously
        ArgumentCaptor<RuleAntipatternEvent> eventCaptor = ArgumentCaptor.forClass(RuleAntipatternEvent.class);
        verify(antipatternBus, atLeastOnce()).fireAsync(eventCaptor.capture());

        RuleAntipatternEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.className()).isEqualTo("com.pulse.bad.BadService");
        assertThat(capturedEvent.antipatternType()).isEqualTo(AntipatternType.EXCESSIVE_OBJECT_CREATION);
        assertThat(capturedEvent.jarAnalysisId()).isEqualTo(20L);

        // Pass event directly into AntipatternObserver and verify audit log entry
        antipatternObserver.onAntipattern(capturedEvent);
        verify(auditLogRepository).logAsync(
                eq("SYSTEM"),
                contains("com.pulse.bad.BadService"),
                eq("EVENT"),
                eq(true)
        );
    }

    @Test
    @DisplayName("Verification 4: TelemetryControl produces valid JVM snapshot and streams via SSE")
    void testTelemetryStreamAndSnapshot() {
        // Test snapshot endpoint
        Response snapshotResponse = telemetryResource.getSnapshot();
        assertThat(snapshotResponse.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(snapshotResponse.getEntity()).isInstanceOf(JvmTelemetrySnapshot.class);

        JvmTelemetrySnapshot snapshot = (JvmTelemetrySnapshot) snapshotResponse.getEntity();
        assertThat(snapshot.heapUsedBytes()).isGreaterThan(0L);
        assertThat(snapshot.heapMaxBytes()).isGreaterThan(0L);
        assertThat(snapshot.heapUsedPercent()).isBetween(0.0, 100.0);
        assertThat(snapshot.threadCount()).isGreaterThan(0);
        assertThat(snapshot.uptimeMs()).isGreaterThan(0L);

        // Test SSE registration
        telemetryResource.stream(sseEventSink, sse);
        verify(sseBroadcaster).register(sseEventSink);

        // Test SSE event broadcasting
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.id(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(any(MediaType.class))).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);

        telemetryControl.sampleAndBroadcast();

        verify(sseBroadcaster).broadcast(outboundEvent);
    }

    @Test
    @DisplayName("Verification 5: Role-based Access Control correctly protects endpoints")
    void testRoleBasedAccessControl() throws NoSuchMethodException {
        // Stream endpoint requires ADMIN
        Method stream = TelemetryResource.class.getMethod("stream", SseEventSink.class, Sse.class);
        assertThat(stream.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");

        // Snapshot endpoint requires ADMIN
        Method snapshot = TelemetryResource.class.getMethod("getSnapshot");
        assertThat(snapshot.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");

        // All JAR sessions overview requires ADMIN
        Method allJarSessions = JarAnalysisResource.class.getMethod("getAllSessions");
        assertThat(allJarSessions.getAnnotation(RolesAllowed.class).value()).containsExactly("ADMIN");

        // Analyze allows both ENGINEER and ADMIN
        Method analyze = JarAnalysisResource.class.getMethod("analyzeMultipart", List.class, SecurityContext.class);
        assertThat(analyze.getAnnotation(RolesAllowed.class).value()).containsExactlyInAnyOrder("ENGINEER", "ADMIN");

        // Get single session allows both ENGINEER and ADMIN
        Method singleSession = JarAnalysisResource.class.getMethod("getSessionById", Long.class);
        assertThat(singleSession.getAnnotation(RolesAllowed.class).value()).containsExactlyInAnyOrder("ENGINEER", "ADMIN");
    }
}
