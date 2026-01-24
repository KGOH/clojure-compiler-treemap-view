package clojure.metrics;

import net.bytebuddy.asm.Advice;

import java.util.HashMap;
import java.util.Map;

/**
 * NEW unified ByteBuddy advice for clojure.lang.Compiler.macroexpand
 *
 * Captures BOTH raw (input) and expanded (output) forms in a single hook:
 * - OnMethodEnter: captures input form as phase="raw"
 * - OnMethodExit: captures return value as phase="expanded"
 *
 * This replaces the old two-hook approach (MacroexpandAdvice + CompilerAdvice).
 */
public class MacroexpandUnifiedAdvice {

    /**
     * ThreadLocal depth counter for macroexpand calls.
     * macroexpand calls itself recursively - we only capture at depth 0.
     */
    public static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Store the input form to compare with output on exit.
     * Only set at depth 0.
     */
    public static final ThreadLocal<Object> INPUT_FORM = new ThreadLocal<>();

    /**
     * Called when entering macroexpand.
     * Captures the INPUT (raw) form at depth 0.
     *
     * @param form The form being expanded
     * @return 0=not ISeq, 1=ISeq but depth>0, 2=ISeq and depth==0 (capture)
     */
    @Advice.OnMethodEnter
    public static int onEnter(@Advice.Argument(0) Object form) {
        if (form == null) {
            return 0;
        }

        // Only process ISeq forms
        try {
            Class<?> iseqClass = Class.forName("clojure.lang.ISeq");
            if (!iseqClass.isInstance(form)) {
                return 0;  // Not ISeq, don't track
            }
        } catch (ClassNotFoundException e) {
            return 0;
        }

        int depth = DEPTH.get();
        DEPTH.set(depth + 1);

        if (depth == 0) {
            // Store input form for exit handler
            INPUT_FORM.set(form);

            // Capture raw form
            try {
                captureForm(form, "raw");
            } catch (Throwable t) {
                // Silently ignore
            }
            return 2;  // ISeq at depth 0, will process on exit
        }

        return 1;  // ISeq but depth > 0, only decrement on exit
    }

    /**
     * Called when exiting macroexpand.
     * Captures the OUTPUT (expanded) form at depth 0.
     *
     * @param enterResult 0=don't decrement, 1=decrement only, 2=decrement and capture
     * @param result The expanded form (return value)
     */
    @Advice.OnMethodExit
    public static void onExit(@Advice.Enter int enterResult,
                              @Advice.Return Object result) {
        // Only decrement if we incremented (enterResult > 0)
        if (enterResult > 0) {
            DEPTH.set(DEPTH.get() - 1);
        }

        // Only capture expanded if we were at depth 0 (enterResult == 2)
        if (enterResult != 2) {
            return;
        }

        // Capture expanded form if different from input
        try {
            Object inputForm = INPUT_FORM.get();
            INPUT_FORM.remove();

            // Only capture expanded if it changed
            if (result != null && result != inputForm) {
                captureForm(result, "expanded");
            }
        } catch (Throwable t) {
            // Silently ignore
        }
    }

    /**
     * Capture def forms and send to MetricsBridge NEW buffer.
     */
    public static void captureForm(Object form, String phase) {
        if (form == null) {
            return;
        }

        try {
            Object first = form.getClass().getMethod("first").invoke(form);
            if (first == null) {
                return;
            }

            String opName = first.toString();

            // Skip namespace changes
            if ("ns".equals(opName) || "in-ns".equals(opName)) {
                return;
            }

            // Only capture def forms
            if (!"def".equals(opName) && !"defn".equals(opName) && !"defn-".equals(opName) &&
                !"defmacro".equals(opName) && !"defmulti".equals(opName) &&
                !"defprotocol".equals(opName) && !"defrecord".equals(opName) &&
                !"deftype".equals(opName) && !"definterface".equals(opName)) {
                return;
            }

            // Get name
            Object rest = form.getClass().getMethod("next").invoke(form);
            if (rest == null) {
                return;
            }

            Object nameSymbol = rest.getClass().getMethod("first").invoke(rest);
            if (nameSymbol == null) {
                return;
            }

            String name = nameSymbol.toString();

            // Get line and namespace
            Integer line = null;
            Integer endLine = null;
            String ns = CompilerUtils.getCurrentNamespace();

            Object meta = CompilerUtils.getMetadata(form);
            if (meta != null) {
                line = CompilerUtils.getMetaInt(meta, "line");
                endLine = CompilerUtils.getMetaInt(meta, "end-line");
            }

            Object nameMeta = CompilerUtils.getMetadata(nameSymbol);
            if (nameMeta != null) {
                if (line == null) {
                    line = CompilerUtils.getMetaInt(nameMeta, "line");
                }
                if (endLine == null) {
                    endLine = CompilerUtils.getMetaInt(nameMeta, "end-line");
                }
            }

            if (line == null) {
                line = CompilerUtils.getCompilerLine();
            }

            // Build captured info
            Map<String, Object> defInfo = new HashMap<>();
            defInfo.put("phase", phase);
            defInfo.put("op", opName);
            defInfo.put("name", name);
            defInfo.put("ns", ns);
            defInfo.put("form", form);
            if (line != null) {
                defInfo.put("line", line);
            }
            if (endLine != null) {
                defInfo.put("end-line", endLine);
            }

            MetricsBridge.capture(defInfo);

        } catch (Exception e) {
            // Silently ignore
        }
    }
}
