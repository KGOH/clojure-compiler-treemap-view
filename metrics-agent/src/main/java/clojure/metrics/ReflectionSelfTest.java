package clojure.metrics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Self-test for reflection patterns used by advice classes.
 *
 * Verifies that the Clojure internal APIs we depend on are accessible.
 * Runs once on first use, prints warning if reflection fails.
 */
public class ReflectionSelfTest {

    private static volatile boolean tested = false;
    private static volatile boolean passed = true;

    /** Check if self-test passed. Returns true if not yet tested or if passed. */
    public static boolean isHealthy() {
        return passed;
    }

    /** Run self-test if not already run. Thread-safe via double-checked locking. */
    public static void ensureTested() {
        if (tested) return;
        synchronized (ReflectionSelfTest.class) {
            if (tested) return;
            runTest();
            tested = true;
        }
    }

    private static void runTest() {
        StringBuilder errors = new StringBuilder();

        // Test 1: Var.ns field (used by VarRefAdvice, ConstVarRefAdvice)
        try {
            Class<?> varClass = Class.forName("clojure.lang.Var");
            Field nsField = varClass.getField("ns");
            if (nsField == null) errors.append("  - Var.ns field is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Var.ns: ").append(e.getMessage()).append("\n");
        }

        // Test 2: Var.sym field (used by VarRefAdvice, ConstVarRefAdvice)
        try {
            Class<?> varClass = Class.forName("clojure.lang.Var");
            Field symField = varClass.getField("sym");
            if (symField == null) errors.append("  - Var.sym field is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Var.sym: ").append(e.getMessage()).append("\n");
        }

        // Test 3: Namespace.getName() method (used by VarRefAdvice, ConstVarRefAdvice)
        try {
            Class<?> nsClass = Class.forName("clojure.lang.Namespace");
            Method getNameMethod = nsClass.getMethod("getName");
            if (getNameMethod == null) errors.append("  - Namespace.getName() is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Namespace.getName(): ").append(e.getMessage()).append("\n");
        }

        // Test 4: Compiler.resolve(Symbol) method (used by ConstVarRefAdvice)
        try {
            Class<?> compilerClass = Class.forName("clojure.lang.Compiler");
            Class<?> symbolClass = Class.forName("clojure.lang.Symbol");
            Method resolveMethod = compilerClass.getDeclaredMethod("resolve", symbolClass);
            resolveMethod.setAccessible(true);
            if (resolveMethod == null) errors.append("  - Compiler.resolve(Symbol) is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Compiler.resolve(): ").append(e.getMessage()).append("\n");
        }

        // Test 5: Var.meta() method (used by ConstVarRefAdvice)
        try {
            Class<?> varClass = Class.forName("clojure.lang.Var");
            Method metaMethod = varClass.getMethod("meta");
            if (metaMethod == null) errors.append("  - Var.meta() is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Var.meta(): ").append(e.getMessage()).append("\n");
        }

        // Test 6: Keyword.intern(String, String) method (used by ConstVarRefAdvice)
        try {
            Class<?> keywordClass = Class.forName("clojure.lang.Keyword");
            Method internMethod = keywordClass.getMethod("intern", String.class, String.class);
            if (internMethod == null) errors.append("  - Keyword.intern() is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access Keyword.intern(): ").append(e.getMessage()).append("\n");
        }

        // Test 7: IPersistentMap.valAt(Object) method (used by ConstVarRefAdvice)
        try {
            Class<?> mapClass = Class.forName("clojure.lang.IPersistentMap");
            Method valAtMethod = mapClass.getMethod("valAt", Object.class);
            if (valAtMethod == null) errors.append("  - IPersistentMap.valAt() is null\n");
        } catch (Exception e) {
            errors.append("  - Cannot access IPersistentMap.valAt(): ").append(e.getMessage()).append("\n");
        }

        if (errors.length() > 0) {
            passed = false;
            System.err.println("[metrics-agent] WARNING: Reflection self-test failed!");
            System.err.println("[metrics-agent] Some hooks may not capture data correctly.");
            System.err.println("[metrics-agent] This usually means Clojure internals have changed.");
            System.err.println("[metrics-agent] Errors:");
            System.err.print(errors);
        }
    }
}
