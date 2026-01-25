package clojure.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between the Java agent and Clojure code.
 *
 * Captured def information is stored in a thread-safe map keyed by
 * (ns, name, phase). This ensures:
 * - Bounded memory (O(unique defs))
 * - Latest version wins when same def is recompiled
 * - No duplicates accumulate
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics MetricsBridge])
 *   (MetricsBridge/drainBuffer)  ; returns and clears all captured defs
 *   (MetricsBridge/peekBuffer)   ; returns without clearing
 */
public class MetricsBridge {

    /**
     * Thread-safe buffer for captured def information.
     * Key: "ns/name/phase" composite key
     * Value: Map with keys: phase, op, name, ns, line, end-line, form
     */
    private static final ConcurrentHashMap<String, Map<String, Object>> BUFFER =
        new ConcurrentHashMap<>();

    /**
     * Flag to enable/disable capturing (useful for filtering).
     */
    private static volatile boolean enabled = true;

    /**
     * Capture a def form. Called by MacroexpandUnifiedAdvice.
     * Uses composite key (ns/name/phase) so latest version overwrites previous.
     *
     * @param defInfo Map containing def metadata
     */
    public static void capture(Map<String, Object> defInfo) {
        if (!enabled || defInfo == null) return;

        String ns = (String) defInfo.get("ns");
        String phase = (String) defInfo.get("phase");
        String name = extractDefName(defInfo.get("form"));

        if (ns == null || name == null || phase == null) return;

        String key = ns + "/" + name + "/" + phase;
        BUFFER.put(key, defInfo);
    }

    /**
     * Extract def name from form like (def name ...) or (defn name ...)
     * Uses reflection to call Clojure seq methods.
     *
     * @param form The Clojure form (typically an ISeq)
     * @return The def name as a string, or null if extraction fails
     */
    private static String extractDefName(Object form) {
        try {
            // form.next().first() gets the second element (the name symbol)
            Object rest = form.getClass().getMethod("next").invoke(form);
            if (rest == null) return null;
            Object second = rest.getClass().getMethod("first").invoke(rest);
            return second != null ? second.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Drain and return all captured defs, clearing the buffer.
     * This is the primary API for Clojure code.
     *
     * @return Collection of captured def maps
     */
    public static Collection<Map<String, Object>> drainBuffer() {
        Collection<Map<String, Object>> result = new ArrayList<>(BUFFER.values());
        BUFFER.clear();
        return result;
    }

    /**
     * Peek at captured defs without clearing the buffer.
     * Useful for debugging or inspection.
     *
     * @return Collection of captured def maps
     */
    public static Collection<Map<String, Object>> peekBuffer() {
        return new ArrayList<>(BUFFER.values());
    }

    /**
     * Clear the buffer without returning contents.
     */
    public static void clearBuffer() {
        BUFFER.clear();
    }

    /**
     * Get the current buffer size.
     *
     * @return Number of captured defs in buffer
     */
    public static int size() {
        return BUFFER.size();
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
