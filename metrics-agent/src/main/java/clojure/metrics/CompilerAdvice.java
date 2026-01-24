package clojure.metrics;

import net.bytebuddy.asm.Advice;

import java.util.HashMap;
import java.util.Map;

/**
 * ByteBuddy advice for clojure.lang.Compiler.analyzeSeq
 *
 * This advice captures def/defn forms as they pass through the compiler,
 * extracting name, namespace, and line number information.
 *
 * The signature of analyzeSeq is:
 *   private static Expr analyzeSeq(C context, ISeq form, String name)
 *
 * We use @Advice.OnMethodEnter to inspect the form parameter before analysis.
 */
public class CompilerAdvice {

    /**
     * ThreadLocal depth counter to avoid capturing nested calls.
     * We only want to capture at depth 0 (top-level def forms).
     * Must be public for ByteBuddy advice inlining.
     */
    public static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Track current namespace being compiled.
     * This is set by observing 'ns' forms.
     * Must be public for ByteBuddy advice inlining.
     */
    public static final ThreadLocal<String> CURRENT_NS = ThreadLocal.withInitial(() -> "user");

    /**
     * Called when entering analyzeSeq.
     *
     * @param form The ISeq form being analyzed (second parameter)
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(1) Object form) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);

        // Only capture at depth 0 to avoid recursive captures
        if (depth > 0) {
            return;
        }

        try {
            captureForm(form);
        } catch (Throwable t) {
            // Silently ignore errors to avoid breaking compilation
        }
    }

    @Advice.OnMethodExit
    public static void onExit() {
        DEPTH.set(DEPTH.get() - 1);
    }

    /**
     * Capture def/defn forms and send to MetricsBridge.
     * Must be public for ByteBuddy advice inlining.
     */
    public static void captureForm(Object form) {
        if (form == null) {
            return;
        }

        // form is an ISeq - get the first element (the operator)
        // We need to use reflection since Clojure classes aren't available at compile time
        try {
            // ISeq.first() returns the operator symbol
            Object first = form.getClass().getMethod("first").invoke(form);
            if (first == null) {
                return;
            }

            String opName = first.toString();

            // Track namespace changes
            if ("ns".equals(opName) || "in-ns".equals(opName)) {
                Object rest = form.getClass().getMethod("next").invoke(form);
                if (rest != null) {
                    Object nsName = rest.getClass().getMethod("first").invoke(rest);
                    if (nsName != null) {
                        String ns = nsName.toString();
                        // Remove quote if present (in-ns 'my.ns)
                        if (ns.startsWith("(quote ")) {
                            ns = ns.substring(7, ns.length() - 1);
                        }
                        CURRENT_NS.set(ns);
                    }
                }
                return;
            }

            // Only capture def and defn forms
            if (!"def".equals(opName) && !"defn".equals(opName) && !"defn-".equals(opName) &&
                !"defmacro".equals(opName) && !"defmulti".equals(opName)) {
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

            // Get line number from metadata or compiler state
            Integer line = null;
            Integer endLine = null;

            // Get current namespace from Clojure runtime
            String ns = getCurrentNamespace();

            // Try to get metadata from the form itself
            Object meta = getMetadata(form);
            if (meta != null) {
                line = getMetaInt(meta, "line");
                endLine = getMetaInt(meta, "end-line");
            }

            // Also check name symbol metadata (often has line info)
            Object nameMeta = getMetadata(nameSymbol);
            if (nameMeta != null) {
                if (line == null) {
                    line = getMetaInt(nameMeta, "line");
                }
                if (endLine == null) {
                    endLine = getMetaInt(nameMeta, "end-line");
                }
            }

            // Fall back to Compiler.LINE if no line metadata found
            if (line == null) {
                line = getCompilerLine();
            }

            // Build the captured def info with phase="expanded"
            Map<String, Object> defInfo = new HashMap<>();
            defInfo.put("phase", "expanded");  // Post-macro-expansion
            defInfo.put("op", opName);
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
            // Uncomment for debugging:
            // System.err.println("[CompilerAdvice] Reflection error: " + e.getMessage());
        }
    }

    /**
     * Get metadata from an object using IMeta interface.
     * Must be public for ByteBuddy advice inlining.
     */
    public static Object getMetadata(Object obj) {
        try {
            // Check if object implements IMeta
            Class<?> iMetaClass = Class.forName("clojure.lang.IMeta");
            if (!iMetaClass.isInstance(obj)) {
                return null;
            }
            return obj.getClass().getMethod("meta").invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get an integer value from metadata map.
     * Must be public for ByteBuddy advice inlining.
     */
    public static Integer getMetaInt(Object meta, String key) {
        try {
            // meta is an IPersistentMap - use valAt
            Class<?> keywordClass = Class.forName("clojure.lang.Keyword");
            Object keyword = keywordClass.getMethod("intern", String.class).invoke(null, key);
            Object value = meta.getClass().getMethod("valAt", Object.class).invoke(meta, keyword);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get a string value from metadata map.
     * Must be public for ByteBuddy advice inlining.
     */
    public static String getMetaString(Object meta, String key) {
        try {
            Class<?> keywordClass = Class.forName("clojure.lang.Keyword");
            Object keyword = keywordClass.getMethod("intern", String.class).invoke(null, key);
            Object value = meta.getClass().getMethod("valAt", Object.class).invoke(meta, keyword);
            if (value != null) {
                return value.toString();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get current line number from Compiler.LINE var.
     * Must be public for ByteBuddy advice inlining.
     */
    public static Integer getCompilerLine() {
        try {
            Class<?> compilerClass = Class.forName("clojure.lang.Compiler");
            java.lang.reflect.Field lineField = compilerClass.getDeclaredField("LINE");
            lineField.setAccessible(true);
            Object lineVar = lineField.get(null);

            // LINE is a Var, deref it
            Object lineValue = lineVar.getClass().getMethod("deref").invoke(lineVar);
            if (lineValue instanceof Number) {
                int line = ((Number) lineValue).intValue();
                if (line > 0) {
                    return line;
                }
            }
        } catch (Exception e) {
            // Ignore - line info not available
        }
        return null;
    }

    /**
     * Get current namespace from Clojure runtime.
     * Must be public for ByteBuddy advice inlining.
     */
    public static String getCurrentNamespace() {
        try {
            // Try to get *ns* var and deref it
            Class<?> rtClass = Class.forName("clojure.lang.RT");
            Class<?> varClass = Class.forName("clojure.lang.Var");

            // RT.CURRENT_NS is the *ns* Var
            java.lang.reflect.Field currentNsField = rtClass.getDeclaredField("CURRENT_NS");
            Object currentNsVar = currentNsField.get(null);

            // Deref the Var to get the Namespace
            Object ns = varClass.getMethod("deref").invoke(currentNsVar);
            if (ns != null) {
                // Namespace.getName() returns a Symbol
                Object nsName = ns.getClass().getMethod("getName").invoke(ns);
                if (nsName != null) {
                    return nsName.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("[CompilerAdvice] Error getting namespace: " + e.getMessage());
        }
        return CURRENT_NS.get();
    }
}
