package clojure.metrics;

/**
 * Utility methods for accessing Clojure compiler state via reflection.
 *
 * These methods are used by MacroexpandUnifiedAdvice to extract metadata
 * from forms during compilation.
 */
public class CompilerUtils {

    /**
     * Get metadata from an object using IMeta interface.
     */
    public static Object getMetadata(Object obj) {
        try {
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
     */
    public static Integer getMetaInt(Object meta, String key) {
        try {
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
     */
    public static String getCurrentNamespace() {
        try {
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
            // Ignore
        }
        return "user";
    }
}
