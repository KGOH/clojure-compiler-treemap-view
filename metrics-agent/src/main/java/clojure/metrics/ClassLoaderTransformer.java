package clojure.metrics;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * ClassFileTransformer that captures all classes as they are loaded.
 *
 * This transformer observes class loading without modifying bytecode.
 * It records class names, bytecode sizes, and field counts for runtime footprint analysis.
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
            int fieldCount = countFields(classfileBuffer);
            ClassLoadBridge.capture(className, classfileBuffer.length, fieldCount);
        }
        // Return null to indicate no transformation - just observing
        return null;
    }

    /**
     * Count fields in a class using ASM.
     *
     * @param bytecode Raw class file bytes
     * @return Number of fields, or -1 if parsing fails
     */
    private int countFields(byte[] bytecode) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            FieldCountVisitor visitor = new FieldCountVisitor();
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            return visitor.getFieldCount();
        } catch (Exception e) {
            return -1;  // Parse failed, signal unknown
        }
    }

    /**
     * ASM visitor that counts fields in a class.
     */
    private static class FieldCountVisitor extends ClassVisitor {
        private int fieldCount = 0;

        FieldCountVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            fieldCount++;
            return null;  // Don't need to visit field contents
        }

        int getFieldCount() {
            return fieldCount;
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
