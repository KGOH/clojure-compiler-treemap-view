# Clojure Reader Proximity: How Close Are We to the Truth?

## The Problem

We want metrics from the REAL compilation pipeline, not a shadow implementation. The concern: external linters are a separate process with a separate interpreter - we have no way to ensure they match actual runtime behavior.

## tools.analyzer.jvm: A Hybrid

| Component | tools.analyzer | Real Compiler |
|-----------|----------------|---------------|
| **Reader** | tools.reader (separate impl) | clojure.lang.LispReader |
| **Macro expansion** | **REAL** - calls actual Vars | Same |
| **Special forms** | Reimplemented in Clojure | clojure.lang.Compiler (Java) |

### The Good: Macro Expansion is Real

```clojure
;; In tools.analyzer.jvm/macroexpand-1 (line 198):
(let [res (apply v form (:locals env) (rest form))]
  ...)
```

Where `v` is the actual `#'clojure.core/when`, `#'clojure.core/->`, etc. The `:raw-forms` in the AST contains the real pre-expansion form.

### The Bad: Special Forms are Shadow

`parse-if`, `parse-fn*`, `parse-let*` are all reimplemented in Clojure, not calling into `clojure.lang.Compiler`.

### Can We Hook the Real Compiler?

**No simple way from Clojure:**

```clojure
;; This does NOTHING for real compilation:
(alter-var-root #'clojure.core/macroexpand-1 my-wrapper)
(eval '(when true "test"))  ;; Wrapper never called!
```

The compiler is Java code that calls `Compiler.macroexpand1()` directly, not through Vars.

## Java Agent Approach

A Java agent can intercept the real compilation at the JVM level.

### The Ideal Intercept Point

`Compiler.analyzeSeq()` (~line 7098 in Compiler.java):

```java
Object me = macroexpand1(form);  // form = before, me = after
if(me != form)
    return analyze(context, me, name);
```

We see both raw and expanded forms in the same method call.

### Architecture

```
clojure-compiler-agent/
├── ClojureCompilerAgent.java    # premain entry point
├── AnalyzeSeqAdvice.java        # ByteBuddy advice for analyzeSeq
└── ExpansionBridge.java         # ThreadLocal → Clojure Var bridge
```

### Data Bridge to Clojure

```java
public class ExpansionBridge {
    public static Var EXPANSION_CALLBACK =
        Var.intern(Symbol.intern("compiler.metrics"),
                   Symbol.intern("*expansion-callback*"));

    public static void afterMacroExpand(Object before, Object after, String name) {
        IFn callback = (IFn) EXPANSION_CALLBACK.deref();
        if (callback != null) {
            callback.invoke(before, after, name);
        }
    }
}
```

### What Agent Would Enable

| Metric | tools.analyzer | Java Agent |
|--------|----------------|------------|
| Expression count (expanded) | ✅ Same | ✅ Same |
| Max depth (expanded) | ✅ Same | ✅ Same |
| Pre-expansion forms | ✅ Via `:raw-forms` | ✅ Direct |
| **Macro expansion ratio** | ❌ No | ✅ Yes |
| **Which macros fired** | ❌ No | ✅ Yes |
| True compilation errors | ❌ Shadow | ✅ Real |

## Verdict: Agent Approach Adopted

We implemented the Java agent approach. Benefits over tools.analyzer:
- Direct access to real compilation pipeline
- No skip-list workarounds for problematic namespaces
- VarExpr hooks for accurate unused var detection
- ClassLoadBridge for bytecode size metrics

The agent hooks `Compiler.macroexpand` (enter=raw, exit=expanded) and `VarExpr`/`TheVarExpr` constructors.

## Key Clojure Compiler Entry Points

For future reference if implementing the agent:

| Method | Purpose |
|--------|---------|
| `Compiler.load(Reader, path, name)` | Main entry for loading source |
| `Compiler.eval(Object form)` | Single form evaluation |
| `Compiler.analyze(C ctx, Object form)` | Core analysis dispatch |
| `Compiler.analyzeSeq(C ctx, ISeq form, String name)` | **Where macro expansion happens** |
| `Compiler.macroexpand1(Object x)` | Single-step expansion |
