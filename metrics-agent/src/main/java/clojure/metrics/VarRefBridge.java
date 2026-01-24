package clojure.metrics;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe storage for var references captured during compilation.
 *
 * Used by VarRefAdvice to collect all var references, enabling
 * unused var detection: unused = defined_vars - referenced_vars
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics VarRefBridge])
 *   (VarRefBridge/getReferences)  ; returns set of "ns/name" strings
 *   (VarRefBridge/clear)          ; resets for new analysis
 */
public class VarRefBridge {

    /**
     * Thread-safe set of referenced var names in "ns/name" format.
     */
    private static final Set<String> REFERENCES = ConcurrentHashMap.newKeySet();

    /**
     * Flag to enable/disable capturing.
     */
    private static volatile boolean enabled = true;

    /**
     * Capture a var reference. Called by VarRefAdvice.
     *
     * @param varName Fully-qualified var name "ns/name"
     */
    public static void captureReference(String varName) {
        if (enabled && varName != null) {
            REFERENCES.add(varName);
        }
    }

    /**
     * Get all captured var references.
     *
     * @return Copy of the reference set
     */
    public static Set<String> getReferences() {
        return new HashSet<>(REFERENCES);
    }

    /**
     * Clear all captured references.
     */
    public static void clear() {
        REFERENCES.clear();
    }

    /**
     * Get the number of captured references.
     *
     * @return Reference count
     */
    public static int size() {
        return REFERENCES.size();
    }

    /**
     * Enable or disable capturing.
     *
     * @param enable true to enable, false to disable
     */
    public static void setEnabled(boolean enable) {
        enabled = enable;
    }

    /**
     * Check if capturing is enabled.
     *
     * @return true if enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }
}
