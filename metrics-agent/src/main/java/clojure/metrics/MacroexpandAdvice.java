package clojure.metrics;

import net.bytebuddy.asm.Advice;

import java.util.HashMap;
import java.util.Map;

/**
 * ByteBuddy advice for clojure.lang.Compiler.macroexpand
 *
 * This advice captures def/defn forms BEFORE macro expansion,
 * providing "raw" metrics as written in source code.
 *
 * The signature of macroexpand is:
 *   static Object macroexpand(Object form)
 *
 * macroexpand recursively calls macroexpand1 until form stops changing.
 * We capture at depth=0 to get the original source form.
 *
 * Example:
 *   Source: (defn foo [] (-> x inc dec))
 *   MacroexpandAdvice sees at depth=0: (defn foo [] (-> x inc dec)) <- raw
 *   After expansion: (def foo (fn* [] (dec (inc x))))
 */
public class MacroexpandAdvice {

    /**
     * ThreadLocal depth counter for macroexpand calls.
     * macroexpand calls itself recursively - we only capture at depth 0.
     * Must be public for ByteBuddy advice inlining.
     */
    public static final ThreadLocal<Integer> MACROEXPAND_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Called when entering macroexpand.
     * Returns true if we should decrement depth on exit.
     *
     * @param form The form being expanded
     * @return true if depth was incremented
     */
    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Argument(0) Object form) {
        // Fast path: only ISeq can be def forms
        if (form == null) {
            return false;
        }

        try {
            Class<?> iseqClass = Class.forName("clojure.lang.ISeq");
            if (!iseqClass.isInstance(form)) {
                return false;  // Don't track depth for non-sequences
            }
        } catch (ClassNotFoundException e) {
            return false;
        }

        int depth = MACROEXPAND_DEPTH.get();
        MACROEXPAND_DEPTH.set(depth + 1);

        // Only capture at depth 0 (first call, before any expansion)
        if (depth == 0) {
            try {
                captureForm(form);
            } catch (Throwable t) {
                // Silently ignore errors to avoid breaking compilation
            }
        }

        return true;  // We incremented depth
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.Enter boolean didIncrement) {
        if (didIncrement) {
            MACROEXPAND_DEPTH.set(MACROEXPAND_DEPTH.get() - 1);
        }
    }

    /**
     * Capture def/defn forms with phase="raw" and send to MetricsBridge.
     * Must be public for ByteBuddy advice inlining.
     */
    public static void captureForm(Object form) {
        if (form == null) {
            return;
        }

        try {
            // ISeq.first() returns the operator symbol
            Object first = form.getClass().getMethod("first").invoke(form);
            if (first == null) {
                return;
            }

            String opName = first.toString();

            // Skip namespace changes
            if ("ns".equals(opName) || "in-ns".equals(opName)) {
                return;
            }

            // Capture def forms - at this point we see the ORIGINAL form
            // e.g., "defn" not "def", "(-> x inc)" not "(dec (inc x))"
            if (!"def".equals(opName) && !"defn".equals(opName) && !"defn-".equals(opName) &&
                !"defmacro".equals(opName) && !"defmulti".equals(opName) &&
                !"defprotocol".equals(opName) && !"defrecord".equals(opName) &&
                !"deftype".equals(opName) && !"definterface".equals(opName)) {
                return;
            }

            // Get the second element (the name symbol)
            Object rest = form.getClass().getMethod("next").invoke(form);
            if (rest == null) {
                return;
            }

            Object nameSymbol = rest.getClass().getMethod("first").invoke(rest);
            if (nameSymbol == null) {
                return;
            }

            String name = nameSymbol.toString();

            // Get line number and namespace using shared helpers
            Integer line = null;
            Integer endLine = null;
            String ns = CompilerAdvice.getCurrentNamespace();

            // Try to get metadata from the form itself
            Object meta = CompilerAdvice.getMetadata(form);
            if (meta != null) {
                line = CompilerAdvice.getMetaInt(meta, "line");
                endLine = CompilerAdvice.getMetaInt(meta, "end-line");
            }

            // Also check name symbol metadata
            Object nameMeta = CompilerAdvice.getMetadata(nameSymbol);
            if (nameMeta != null) {
                if (line == null) {
                    line = CompilerAdvice.getMetaInt(nameMeta, "line");
                }
                if (endLine == null) {
                    endLine = CompilerAdvice.getMetaInt(nameMeta, "end-line");
                }
            }

            // Fall back to Compiler.LINE
            if (line == null) {
                line = CompilerAdvice.getCompilerLine();
            }

            // Build the captured def info with phase="raw"
            Map<String, Object> defInfo = new HashMap<>();
            defInfo.put("phase", "raw");  // Pre-macro-expansion
            defInfo.put("op", opName);     // Will be "defn" not "def"
            defInfo.put("name", name);
            defInfo.put("ns", ns);
            defInfo.put("form", form);  // Store form object directly, compute metrics lazily in Clojure
            if (line != null) {
                defInfo.put("line", line);
            }
            if (endLine != null) {
                defInfo.put("end-line", endLine);
            }

            MetricsBridge.capture(defInfo);

        } catch (Exception e) {
            // Silently ignore reflection errors
        }
    }
}
