package com.pulse.control;

import com.pulse.entity.AnalysisClassMetric;
import com.pulse.entity.AnalysisStatus;
import com.pulse.entity.JarAnalysis;
import jakarta.enterprise.event.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JarAnalysisControlTest {

    @Mock
    private EntityManager em;

    @Mock
    private Event<RuleAntipatternEvent> antipatternBus;

    @Mock
    private TypedQuery<JarAnalysis> typedQuery;

    private JarAnalysisControl control;

    @BeforeEach
    void setUp() {
        control = new JarAnalysisControl(em, Runnable::run, antipatternBus);
    }

    private byte[] createSampleJar(boolean includeAntipattern) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/sample/TestEngine", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
            mv.visitCode();

            if (includeAntipattern) {
                // Generate 55 NEW opcodes to trigger EXCESSIVE_OBJECT_CREATION
                for (int i = 0; i < 55; i++) {
                    mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    mv.visitInsn(Opcodes.POP);
                }
            }

            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();

            jos.putNextEntry(new JarEntry("com/pulse/sample/TestEngine.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();

            // Add non-class entry to ensure it's ignored
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jos.write("Manifest-Version: 1.0\n".getBytes());
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("submitAsync persists PENDING analysis, runs async task, and returns generated ID")
    void testSubmitAsync() throws Exception {
        byte[] jarBytes = createSampleJar(false);

        doAnswer(invocation -> {
            JarAnalysis analysis = invocation.getArgument(0);
            Field idField = JarAnalysis.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(analysis, 101L);
            return null;
        }).when(em).persist(any(JarAnalysis.class));

        JarAnalysis mockFind = new JarAnalysis("sample.jar", "engineer_1");
        when(em.find(JarAnalysis.class, 101L)).thenReturn(mockFind);

        Long id = control.submitAsync(new ByteArrayInputStream(jarBytes), "sample.jar", "engineer_1");

        assertThat(id).isEqualTo(101L);
        verify(em).persist(any(JarAnalysis.class));
        verify(em).flush();
        verify(em).merge(mockFind);
        assertThat(mockFind.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(mockFind.getClassCount()).isEqualTo(1);
        assertThat(mockFind.getTotalOpcodes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("analyze fires antipattern event when bytecode contains antipattern")
    void testAnalyzeFiresAntipatternEvent() throws Exception {
        byte[] jarBytes = createSampleJar(true);

        JarAnalysis analysis = new JarAnalysis("bad.jar", "engineer_1");
        when(em.find(JarAnalysis.class, 50L)).thenReturn(analysis);

        control.analyze(new ByteArrayInputStream(jarBytes), 50L);

        verify(antipatternBus, atLeastOnce()).fireAsync(any(RuleAntipatternEvent.class));
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getAntipatternCount()).isGreaterThan(0);
        assertThat(analysis.getClassMetrics()).hasSize(1);
        assertThat(analysis.getClassMetrics().get(0).isHasAntipattern()).isTrue();
    }

    @Test
    @DisplayName("analyze sets status to FAILED when inputStream is unparseable")
    void testAnalyzeFailsOnCorruptJar() {
        JarAnalysis analysis = new JarAnalysis("corrupt.jar", "engineer_1");
        when(em.find(JarAnalysis.class, 99L)).thenReturn(analysis);

        byte[] corruptBytes = "not-a-real-jar-file".getBytes();
        // Since JarInputStream may not throw on plain bytes but read 0 entries,
        // let's pass a stream that throws IOException
        InputStream faultyStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated disk read error");
            }
        };

        control.analyze(faultyStream, 99L);

        verify(em).merge(analysis);
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    @DisplayName("findById returns Optional containing JarAnalysis if present")
    void testFindById() {
        JarAnalysis analysis = new JarAnalysis("test.jar", "eng_1");
        when(em.find(JarAnalysis.class, 1L)).thenReturn(analysis);

        Optional<JarAnalysis> found = control.findById(1L);
        assertThat(found).isPresent();
        assertThat(found.get()).isSameAs(analysis);

        assertThat(control.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("findAll executes JPQL query and returns results")
    void testFindAll() {
        when(em.createQuery(anyString(), eq(JarAnalysis.class))).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Collections.emptyList());

        List<JarAnalysis> list = control.findAll();
        assertThat(list).isEmpty();
        verify(em).createQuery("SELECT j FROM JarAnalysis j ORDER BY j.submittedAt DESC", JarAnalysis.class);
    }
}
