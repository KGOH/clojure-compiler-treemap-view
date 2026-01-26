package clojure.metrics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe storage for var references captured during compilation.
 *
 * Used by VarRefAdvice to collect all var references, enabling:
 * - Unused var detection: unused = defined_vars - referenced_vars
 * - Fan-in metrics: count of unique callers per function
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics VarRefBridge])
 *   (VarRefBridge/getReferences)  ; returns set of "ns/name" strings (callees)
 *   (VarRefBridge/getCallerMap)   ; returns map of callee -> #{callers}
 *   (VarRefBridge/clear)          ; resets for new analysis
 */
public class VarRefBridge {

    /**
     * Thread-safe map of callee var -> set of caller vars.
     * Both keys and values are "ns/name" format strings.
     * A callee with no callers (top-level reference) will have an empty set.
     */
    private static final ConcurrentHashMap<String, Set<String>> CALLER_MAP = new ConcurrentHashMap<>();

    /**
     * Flag to enable/disable capturing.
     */
    private static volatile boolean enabled = true;

    /**
     * Capture a var reference with caller context. Called by VarRefAdvice.
     *
     * @param callee Fully-qualified var name being referenced "ns/name"
     * @param caller Fully-qualified var name of the calling function "ns/name", or null for top-level
     */
    public static void captureReference(String callee, String caller) {
        if (enabled && callee != null) {
            CALLER_MAP.computeIfAbsent(callee, k -> ConcurrentHashMap.newKeySet());
            if (caller != null) {
                CALLER_MAP.get(callee).add(caller);
            }
        }
    }

    /**
     * Get all captured var references (callees only).
     * Backward compatible with unused var detection.
     *
     * @return Copy of the callee set
     */
    public static Set<String> getReferences() {
        return new HashSet<>(CALLER_MAP.keySet());
    }

    /**
     * Get the full caller map for fan-in metrics.
     *
     * @return Copy of map: callee -> set of callers
     */
    public static Map<String, Set<String>> getCallerMap() {
        Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : CALLER_MAP.entrySet()) {
            result.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return result;
    }

    /**
     * Clear all captured references.
     */
    public static void clear() {
        CALLER_MAP.clear();
    }

    /**
     * Get the number of captured references (unique callees).
     *
     * @return Reference count
     */
    public static int size() {
        return CALLER_MAP.size();
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
