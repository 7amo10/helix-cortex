package com.pulse.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.assertj.core.api.Assertions.assertThat;

class OpcodeCountingVisitorTest {

    @Test
    @DisplayName("Counts opcodes accurately for basic method")
    void testBasicOpcodeCounting() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/test/SampleClass", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", "(II)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] bytecode = cw.toByteArray();
        OpcodeCountingVisitor visitor = new OpcodeCountingVisitor();
        new ClassReader(bytecode).accept(visitor, 0);

        assertThat(visitor.getClassName()).isEqualTo("com.pulse.test.SampleClass");
        assertThat(visitor.getTotalOpcodes()).isEqualTo(4);
        assertThat(visitor.hasAnyAntipattern()).isFalse();
    }

    @Test
    @DisplayName("Detects STRING_CONCAT_LOOP antipattern when StringBuilder.append > 20 times")
    void testStringConcatLoopDetection() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/test/ConcatClass", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "build", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);

        for (int i = 0; i < 25; i++) {
            mv.visitLdcInsn("item" + i);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false
            );
        }

        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        OpcodeCountingVisitor visitor = new OpcodeCountingVisitor();
        new ClassReader(cw.toByteArray()).accept(visitor, 0);

        assertThat(visitor.isHasStringConcatLoop()).isTrue();
        assertThat(visitor.hasAnyAntipattern()).isTrue();
        assertThat(visitor.getDetectedAntipatterns()).contains(AntipatternType.STRING_CONCAT_LOOP);
    }

    @Test
    @DisplayName("Detects EXCESSIVE_OBJECT_CREATION antipattern when NEW instructions > 50")
    void testExcessiveObjectCreationDetection() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/test/AllocClass", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "alloc", "()V", null, null);
        mv.visitCode();

        for (int i = 0; i < 55; i++) {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.POP);
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        OpcodeCountingVisitor visitor = new OpcodeCountingVisitor();
        new ClassReader(cw.toByteArray()).accept(visitor, 0);

        assertThat(visitor.isHasExcessiveObjectCreation()).isTrue();
        assertThat(visitor.hasAnyAntipattern()).isTrue();
        assertThat(visitor.getDetectedAntipatterns()).contains(AntipatternType.EXCESSIVE_OBJECT_CREATION);
    }

    @Test
    @DisplayName("Detects REDUNDANT_INSTANCEOF antipattern when consecutive INSTANCEOF > 10")
    void testRedundantInstanceofDetection() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/pulse/test/InstanceofClass", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", "()V", null, null);
        mv.visitCode();

        for (int i = 0; i < 12; i++) {
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String");
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        OpcodeCountingVisitor visitor = new OpcodeCountingVisitor();
        new ClassReader(cw.toByteArray()).accept(visitor, 0);

        assertThat(visitor.isHasRedundantInstanceof()).isTrue();
        assertThat(visitor.hasAnyAntipattern()).isTrue();
        assertThat(visitor.getDetectedAntipatterns()).contains(AntipatternType.REDUNDANT_INSTANCEOF);
    }
}
