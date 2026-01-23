package clojure.metrics;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ClassFileTransformer that captures all classes as they are loaded.
 *
 * This transformer observes class loading without modifying bytecode.
 * It records class names and bytecode sizes for runtime footprint analysis.
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
            ClassLoadBridge.capture(className, classfileBuffer.length);
        }
        // Return null to indicate no transformation - just observing
        return null;
    }

    /**
     * Filter out JDK and internal classes to reduce noise.
     * Class names use internal format with '/' separators.
     */
    private boolean shouldCapture(String className) {
        // JDK core classes
        if (className.startsWith("java/")) return false;
        if (className.startsWith("javax/")) return false;
        if (className.startsWith("sun/")) return false;
        if (className.startsWith("jdk/")) return false;
        if (className.startsWith("com/sun/")) return false;

        // JVM internals
        if (className.startsWith("jdk/internal/")) return false;

        // ByteBuddy internals (from our compiler hook)
        if (className.contains("bytebuddy")) return false;

        // Our own agent classes
        if (className.startsWith("clojure/metrics/")) return false;

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
