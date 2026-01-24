package clojure.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridge between the Java agent and Clojure code.
 *
 * Captured def information is buffered in a thread-safe queue
 * and can be drained by Clojure code via the static API.
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics MetricsBridge])
 *   (MetricsBridge/drainBuffer)  ; returns and clears all captured defs
 *   (MetricsBridge/peekBuffer)   ; returns without clearing
 */
public class MetricsBridge {

    /**
     * Thread-safe buffer for captured def information.
     * Each entry is a Map with keys: phase, op, name, ns, line, end-line, form
     */
    private static final ConcurrentLinkedQueue<Map<String, Object>> BUFFER =
        new ConcurrentLinkedQueue<>();

    /**
     * Flag to enable/disable capturing (useful for filtering).
     */
    private static volatile boolean enabled = true;

    /**
     * Capture a def form. Called by MacroexpandUnifiedAdvice.
     *
     * @param defInfo Map containing def metadata
     */
    public static void capture(Map<String, Object> defInfo) {
        if (enabled && defInfo != null) {
            BUFFER.offer(defInfo);
        }
    }

    /**
     * Drain and return all captured defs, clearing the buffer.
     * This is the primary API for Clojure code.
     *
     * @return List of captured def maps
     */
    public static List<Map<String, Object>> drainBuffer() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> item;
        while ((item = BUFFER.poll()) != null) {
            result.add(item);
        }
        return result;
    }

    /**
     * Peek at captured defs without clearing the buffer.
     * Useful for debugging or inspection.
     *
     * @return Unmodifiable list of captured def maps
     */
    public static List<Map<String, Object>> peekBuffer() {
        return Collections.unmodifiableList(new ArrayList<>(BUFFER));
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
    public static int bufferSize() {
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
