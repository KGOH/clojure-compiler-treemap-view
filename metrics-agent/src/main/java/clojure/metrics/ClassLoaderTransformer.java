package clojure.metrics;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * ClassFileTransformer that captures all classes as they are loaded.
 *
 * This transformer observes class loading without modifying bytecode.
 * It records class names, bytecode sizes, field counts, and instruction counts
 * for runtime footprint analysis.
 */
public class ClassLoaderTransformer implements ClassFileTransformer {

    private static volatile boolean enabled = true;

    @Override
    public byte[] transform(ClassLoader loader,
                           String className,
                           Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {
        if (enabled && className != null && shouldCapture(className)) {
            int[] metrics = collectMetrics(classfileBuffer);
            ClassLoadBridge.capture(className, classfileBuffer.length, metrics[0], metrics[1]);
        }
        // Return null to indicate no transformation - just observing
        return null;
    }

    /**
     * Collect all metrics in a single pass.
     *
     * @param bytecode Raw class file bytes
     * @return int[2]: [fieldCount, instructionCount], or [-1, -1] on failure
     */
    private int[] collectMetrics(byte[] bytecode) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassMetricsVisitor visitor = new ClassMetricsVisitor();
            // SKIP_DEBUG is fine, but NOT SKIP_CODE - we need method bodies for instruction count
            reader.accept(visitor, ClassReader.SKIP_DEBUG);
            return new int[] { visitor.getFieldCount(), visitor.getInstructionCount() };
        } catch (Exception e) {
            return new int[] { -1, -1 };
        }
    }

    /**
     * Unified visitor that collects all class metrics in one pass.
     */
    private static class ClassMetricsVisitor extends ClassVisitor {
        private int fieldCount = 0;
        private int instructionCount = 0;

        ClassMetricsVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            fieldCount++;
            return null;  // Don't need to visit field contents
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Return a visitor that counts instructions
            return new InstructionCountingVisitor();
        }

        int getFieldCount() {
            return fieldCount;
        }

        int getInstructionCount() {
            return instructionCount;
        }

        /**
         * MethodVisitor that counts all instructions.
         * Each visit*Insn method represents one JVM opcode.
         */
        private class InstructionCountingVisitor extends MethodVisitor {

            InstructionCountingVisitor() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitInsn(int opcode) {
                instructionCount++;
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                instructionCount++;
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                instructionCount++;
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                instructionCount++;
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                instructionCount++;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                        String descriptor, boolean isInterface) {
                instructionCount++;
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor,
                                               Handle bootstrapMethodHandle,
                                               Object... bootstrapMethodArguments) {
                instructionCount++;
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                instructionCount++;
            }

            @Override
            public void visitLdcInsn(Object value) {
                instructionCount++;
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                instructionCount++;
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                instructionCount++;
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                instructionCount++;
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                instructionCount++;
            }
        }
    }

    /**
     * Filter out JDK and internal classes to reduce noise.
     * Class names use internal format with '/' separators.
     */
    private boolean shouldCapture(String className) {
        // Capture everything - filtering is done in the UI
        return true;
    }

    /**
     * Enable or disable class capture.
     */
    public static void setEnabled(boolean enable) {
        enabled = enable;
    }

    /**
     * Check if capture is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }
}
