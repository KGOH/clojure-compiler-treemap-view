package clojure.metrics;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for capturing const var references during compilation.
 *
 * Hooks into Compiler.analyzeSymbol(Symbol) which is called for every symbol.
 * This captures const var references that would otherwise be missed because
 * Clojure inlines ^:const values (no VarExpr is created for them).
 *
 * We call resolve() ourselves before the const check to capture the var reference.
 */
public class ConstVarRefAdvice {

    /** Debug flag - set via -Dclojure.metrics.debug=true */
    public static final boolean DEBUG = Boolean.getBoolean("clojure.metrics.debug");

    /**
     * Called at entry of analyzeSymbol, before const inlining decision.
     *
     * @param sym The Symbol being analyzed
     */
    /** Cached :const keyword */
    public static Object CONST_KEY = null;

    public static Object getConstKey() throws Exception {
        if (CONST_KEY == null) {
            // Create Keyword.intern(null, "const")
            Class<?> keywordClass = Class.forName("clojure.lang.Keyword");
            java.lang.reflect.Method internMethod = keywordClass.getMethod("intern", String.class, String.class);
            CONST_KEY = internMethod.invoke(null, null, "const");
        }
        return CONST_KEY;
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object sym) {
        try {
            if (sym == null) return;

            // Call Compiler.resolve(sym) to see what this symbol resolves to
            Class<?> compilerClass = Class.forName("clojure.lang.Compiler");
            Class<?> symbolClass = Class.forName("clojure.lang.Symbol");

            java.lang.reflect.Method resolveMethod = compilerClass.getDeclaredMethod("resolve", symbolClass);
            resolveMethod.setAccessible(true);

            Object resolved;
            try {
                resolved = resolveMethod.invoke(null, sym);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                // resolve() throws for unresolved symbols - this is expected, ignore
                return;
            }

            // Check if it resolved to a Var
            if (resolved != null && resolved.getClass().getName().equals("clojure.lang.Var")) {
                // Check if it's a const var
                Object meta = resolved.getClass().getMethod("meta").invoke(resolved);
                if (meta != null) {
                    Object constKey = getConstKey();

                    // Check if :const is set in metadata
                    java.lang.reflect.Method valAtMethod = meta.getClass().getMethod("valAt", Object.class);
                    Object constVal = valAtMethod.invoke(meta, constKey);

                    if (constVal != null && !Boolean.FALSE.equals(constVal)) {
                        // It's a const var - capture it since VarExpr won't be created
                        Object ns = resolved.getClass().getField("ns").get(resolved);
                        Object nsName = ns.getClass().getMethod("getName").invoke(ns);
                        Object varSym = resolved.getClass().getField("sym").get(resolved);

                        String varName = nsName.toString() + "/" + varSym.toString();
                        VarRefBridge.captureReference(varName);

                        if (DEBUG) {
                            System.err.println("[metrics-agent] Captured const var: " + varName);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Never let exceptions escape from advice - silently ignore
            if (DEBUG) {
                System.err.println("[metrics-agent] ConstVarRefAdvice failed: " + t.getMessage());
            }
        }
    }
}
