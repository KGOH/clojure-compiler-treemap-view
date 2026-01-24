package clojure.metrics;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for capturing var references during compilation.
 *
 * Hooks into:
 * - Compiler$VarExpr constructor: all var references (my-fn, ns/fn)
 * - Compiler$TheVarExpr constructor: var quotes (#'my-fn)
 *
 * Both constructors take a Var as their first argument.
 * We extract the fully-qualified name and store it in VarRefBridge.
 */
public class VarRefAdvice {

    /**
     * Called when VarExpr or TheVarExpr is constructed.
     *
     * @param var The clojure.lang.Var being referenced
     */
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object var) {
        if (var == null) return;

        try {
            // Extract: var.ns.name + "/" + var.sym.name
            // Var has fields: public final Namespace ns; public final Symbol sym;
            Object ns = var.getClass().getField("ns").get(var);
            if (ns == null) return;

            Object nsName = ns.getClass().getMethod("getName").invoke(ns);
            if (nsName == null) return;

            Object sym = var.getClass().getField("sym").get(var);
            if (sym == null) return;

            String varName = nsName.toString() + "/" + sym.toString();
            VarRefBridge.captureReference(varName);
        } catch (Exception e) {
            // Silently ignore - reflection may fail in edge cases
        }
    }
}
