package clojure.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridge between the Java agent and Clojure code.
 *
 * Captured def information is buffered in thread-safe queues
 * and can be drained by Clojure code via the static API.
 *
 * During migration, we maintain two buffers:
 * - BUFFER_OLD: captures from old hooks (MacroexpandAdvice + CompilerAdvice)
 * - BUFFER_NEW: captures from new unified hook (MacroexpandUnifiedAdvice)
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics MetricsBridge])
 *   (MetricsBridge/drainBufferOld)  ; old implementation
 *   (MetricsBridge/drainBufferNew)  ; new implementation
 *   (MetricsBridge/drainBuffer)     ; alias for old (for compatibility)
 */
public class MetricsBridge {

    /**
     * Buffer for OLD implementation (MacroexpandAdvice + CompilerAdvice)
     */
    private static final ConcurrentLinkedQueue<Map<String, Object>> BUFFER_OLD =
        new ConcurrentLinkedQueue<>();

    /**
     * Buffer for NEW implementation (MacroexpandUnifiedAdvice)
     */
    private static final ConcurrentLinkedQueue<Map<String, Object>> BUFFER_NEW =
        new ConcurrentLinkedQueue<>();

    /**
     * Flag to enable/disable capturing (useful for filtering).
     */
    private static volatile boolean enabled = true;

    /**
     * Capture to OLD buffer. Called by MacroexpandAdvice and CompilerAdvice.
     */
    public static void captureOld(Map<String, Object> defInfo) {
        if (enabled && defInfo != null) {
            BUFFER_OLD.offer(defInfo);
        }
    }

    /**
     * Capture to NEW buffer. Called by MacroexpandUnifiedAdvice.
     */
    public static void captureNew(Map<String, Object> defInfo) {
        if (enabled && defInfo != null) {
            BUFFER_NEW.offer(defInfo);
        }
    }

    /**
     * Legacy capture method - routes to OLD buffer for compatibility.
     */
    public static void capture(Map<String, Object> defInfo) {
        captureOld(defInfo);
    }

    /**
     * Drain OLD buffer.
     */
    public static List<Map<String, Object>> drainBufferOld() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> item;
        while ((item = BUFFER_OLD.poll()) != null) {
            result.add(item);
        }
        return result;
    }

    /**
     * Drain NEW buffer.
     */
    public static List<Map<String, Object>> drainBufferNew() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> item;
        while ((item = BUFFER_NEW.poll()) != null) {
            result.add(item);
        }
        return result;
    }

    /**
     * Legacy drain - routes to OLD buffer for compatibility.
     */
    public static List<Map<String, Object>> drainBuffer() {
        return drainBufferOld();
    }

    /**
     * Peek OLD buffer.
     */
    public static List<Map<String, Object>> peekBufferOld() {
        return Collections.unmodifiableList(new ArrayList<>(BUFFER_OLD));
    }

    /**
     * Peek NEW buffer.
     */
    public static List<Map<String, Object>> peekBufferNew() {
        return Collections.unmodifiableList(new ArrayList<>(BUFFER_NEW));
    }

    /**
     * Legacy peek - routes to OLD buffer for compatibility.
     */
    public static List<Map<String, Object>> peekBuffer() {
        return peekBufferOld();
    }

    /**
     * Clear both buffers.
     */
    public static void clearBuffer() {
        BUFFER_OLD.clear();
        BUFFER_NEW.clear();
    }

    /**
     * Get combined buffer size.
     */
    public static int bufferSize() {
        return BUFFER_OLD.size() + BUFFER_NEW.size();
    }

    public static int bufferSizeOld() {
        return BUFFER_OLD.size();
    }

    public static int bufferSizeNew() {
        return BUFFER_NEW.size();
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
