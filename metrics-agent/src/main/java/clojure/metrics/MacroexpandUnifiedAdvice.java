package clojure.metrics;

import net.bytebuddy.asm.Advice;

import java.util.HashMap;
import java.util.Map;

/**
 * ByteBuddy advice for clojure.lang.Compiler.macroexpand
 *
 * Captures def forms at compilation time with minimal processing:
 * - OnMethodEnter: captures input form as phase="raw"
 * - OnMethodExit: captures output form as phase="expanded" (if changed)
 *
 * Only captures: form, phase, ns (thread-bound), compiler-line (thread-bound)
 * Clojure extracts everything else (op, name, metadata) from the form.
 */
public class MacroexpandUnifiedAdvice {

    /** Depth counter - only capture at depth 0 (top-level forms) */
    public static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** Store input form to compare with output */
    public static final ThreadLocal<Object> INPUT_FORM = new ThreadLocal<>();

    /** Store namespace at entry (thread-bound, may change) */
    public static final ThreadLocal<String> ENTRY_NS = new ThreadLocal<>();

    /** Store compiler line at entry (thread-bound, may change) */
    public static final ThreadLocal<Integer> ENTRY_LINE = new ThreadLocal<>();

    /** Debug flag - set via -Dclojure.metrics.debug=true */
    public static final boolean DEBUG = Boolean.getBoolean("clojure.metrics.debug");

    /**
     * @return 0=skip, 1=decrement only, 2=decrement and capture
     */
    @Advice.OnMethodEnter
    public static int onEnter(@Advice.Argument(0) Object form) {
        if (form == null) return 0;

        // Only track ISeq forms
        try {
            if (!Class.forName("clojure.lang.ISeq").isInstance(form)) {
                return 0;
            }
        } catch (ClassNotFoundException e) {
            return 0;
        }

        int depth = DEPTH.get();
        DEPTH.set(depth + 1);

        if (depth == 0) {
            // Check if it's a def form worth capturing
            if (!isDefForm(form)) {
                return 1;  // Not a def, just track depth
            }

            // Capture thread-bound state NOW (will change later)
            INPUT_FORM.set(form);
            ENTRY_NS.set(getCurrentNamespace());
            ENTRY_LINE.set(getCompilerLine());

            // Capture raw form
            capture(form, "raw");
            return 2;
        }

        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.Enter int enterResult, @Advice.Return Object result) {
        if (enterResult > 0) {
            DEPTH.set(DEPTH.get() - 1);
        }

        if (enterResult == 2) {
            Object inputForm = INPUT_FORM.get();
            INPUT_FORM.remove();

            // Capture expanded if it changed
            if (result != null && result != inputForm) {
                capture(result, "expanded");
            }

            ENTRY_NS.remove();
            ENTRY_LINE.remove();
        }
    }

    /**
     * Check if form is a def-like form worth capturing.
     */
    public static boolean isDefForm(Object form) {
        try {
            Object first = form.getClass().getMethod("first").invoke(form);
            if (first == null) return false;

            String op = first.toString();
            return "def".equals(op) || "defn".equals(op) || "defn-".equals(op) ||
                   "defmacro".equals(op) || "defmulti".equals(op) ||
                   "defprotocol".equals(op) || "defrecord".equals(op) ||
                   "deftype".equals(op) || "definterface".equals(op);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Capture form with minimal data - Clojure extracts the rest.
     */
    public static void capture(Object form, String phase) {
        try {
            Map<String, Object> info = new HashMap<>();
            info.put("form", form);
            info.put("phase", phase);
            info.put("ns", ENTRY_NS.get());

            Integer line = ENTRY_LINE.get();
            if (line != null) {
                info.put("compiler-line", line);
            }

            MetricsBridge.capture(info);
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("[metrics-agent] capture() failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }

    /** Get current namespace from RT.CURRENT_NS */
    public static String getCurrentNamespace() {
        try {
            Class<?> rtClass = Class.forName("clojure.lang.RT");
            Object nsVar = rtClass.getField("CURRENT_NS").get(null);
            Object ns = nsVar.getClass().getMethod("deref").invoke(nsVar);
            if (ns != null) {
                Object name = ns.getClass().getMethod("getName").invoke(ns);
                if (name != null) return name.toString();
            }
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("[metrics-agent] getCurrentNamespace() failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        return "user";
    }

    /** Get current line from Compiler.LINE */
    public static Integer getCompilerLine() {
        try {
            Class<?> compilerClass = Class.forName("clojure.lang.Compiler");
            java.lang.reflect.Field field = compilerClass.getDeclaredField("LINE");
            field.setAccessible(true);
            Object lineVar = field.get(null);
            Object value = lineVar.getClass().getMethod("deref").invoke(lineVar);
            if (value instanceof Number) {
                int line = ((Number) value).intValue();
                if (line > 0) return line;
            }
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("[metrics-agent] getCompilerLine() failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        return null;
    }
}
