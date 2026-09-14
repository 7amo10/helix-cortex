package com.pulse.control;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ASM ClassVisitor that counts total instructions and detects bytecode antipatterns:
 * - STRING_CONCAT_LOOP: > 20 StringBuilder/StringBuffer.append INVOKEVIRTUAL instructions.
 * - EXCESSIVE_OBJECT_CREATION: > 50 NEW instructions.
 * - REDUNDANT_INSTANCEOF: > 10 consecutive or sequential INSTANCEOF instructions.
 */
public class OpcodeCountingVisitor extends ClassVisitor {

    private String className;
    private int totalOpcodes = 0;
    private int stringConcatCount = 0;
    private int newOpcodeCount = 0;
    private int consecutiveInstanceOf = 0;
    private int maxConsecutiveInstanceOf = 0;

    private boolean hasStringConcatLoop = false;
    private boolean hasExcessiveObjectCreation = false;
    private boolean hasRedundantInstanceof = false;

    public OpcodeCountingVisitor() {
        super(Opcodes.ASM9);
    }

    public OpcodeCountingVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name != null ? name.replace('/', '.') : "";
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new OpcodeCountingMethodVisitor(mv);
    }

    public String getClassName() {
        return className;
    }

    public int getTotalOpcodes() {
        return totalOpcodes;
    }

    public boolean isHasStringConcatLoop() {
        return hasStringConcatLoop;
    }

    public boolean isHasExcessiveObjectCreation() {
        return hasExcessiveObjectCreation;
    }

    public boolean isHasRedundantInstanceof() {
        return hasRedundantInstanceof;
    }

    public boolean hasAnyAntipattern() {
        return hasStringConcatLoop || hasExcessiveObjectCreation || hasRedundantInstanceof;
    }

    public List<String> getDetectedAntipatterns() {
        List<String> list = new ArrayList<>();
        if (hasStringConcatLoop) {
            list.add(AntipatternType.STRING_CONCAT_LOOP);
        }
        if (hasExcessiveObjectCreation) {
            list.add(AntipatternType.EXCESSIVE_OBJECT_CREATION);
        }
        if (hasRedundantInstanceof) {
            list.add(AntipatternType.REDUNDANT_INSTANCEOF);
        }
        return Collections.unmodifiableList(list);
    }

    private class OpcodeCountingMethodVisitor extends MethodVisitor {

        OpcodeCountingMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        private void recordInstruction(int opcode) {
            totalOpcodes++;
            if (opcode != Opcodes.INSTANCEOF) {
                consecutiveInstanceOf = 0;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            recordInstruction(opcode);
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            recordInstruction(opcode);
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            recordInstruction(opcode);
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            totalOpcodes++;
            if (opcode == Opcodes.NEW) {
                newOpcodeCount++;
                if (newOpcodeCount > 50) {
                    hasExcessiveObjectCreation = true;
                }
                consecutiveInstanceOf = 0;
            } else if (opcode == Opcodes.INSTANCEOF) {
                consecutiveInstanceOf++;
                if (consecutiveInstanceOf > maxConsecutiveInstanceOf) {
                    maxConsecutiveInstanceOf = consecutiveInstanceOf;
                }
                if (consecutiveInstanceOf > 10) {
                    hasRedundantInstanceof = true;
                }
            } else {
                consecutiveInstanceOf = 0;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            recordInstruction(opcode);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            recordInstruction(opcode);
            if (opcode == Opcodes.INVOKEVIRTUAL &&
                    ("java/lang/StringBuilder".equals(owner) || "java/lang/StringBuffer".equals(owner)) &&
                    "append".equals(name)) {
                stringConcatCount++;
                if (stringConcatCount > 20) {
                    hasStringConcatLoop = true;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            recordInstruction(Opcodes.INVOKEDYNAMIC);
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            recordInstruction(opcode);
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            recordInstruction(Opcodes.LDC);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            recordInstruction(Opcodes.IINC);
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            recordInstruction(Opcodes.TABLESWITCH);
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            recordInstruction(Opcodes.LOOKUPSWITCH);
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            recordInstruction(Opcodes.MULTIANEWARRAY);
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
    }
}
