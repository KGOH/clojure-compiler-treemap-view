# Plan: Replace tools.analyzer.jvm with Compiler Hook

## Goal

Remove tools.analyzer.jvm dependency and compute all metrics from:
1. **Raw metrics**: Hook `macroexpand` to capture pre-expansion forms
2. **Expanded metrics**: Hook `analyzeSeq` to capture post-expansion forms
3. **Unused detection**: Hook `registerVar` to track var references

**Principle**: Be true to the source of truth - capture what the compiler actually processes.

---

## Key Insight: Dual-Hook Architecture

The Clojure compiler has two stages we can hook:

```
Source: (defn foo [] (-> x inc dec))
              ↓
        macroexpand (hook point 1 - "raw")
              ↓
Hook sees: (defn foo [] (-> x inc dec))   <- phase="raw"
              ↓
        macroexpand1 (recursively expands)
              ↓
        analyzeSeq (hook point 2 - "expanded")
              ↓
Hook sees: (def foo (fn* [] (dec (inc x)))) <- phase="expanded"
```

**PoC Verified** (2024-01): Dual hooks working:
- `MacroexpandAdvice` → `defn`, `defn-`, `defmacro`, `defmulti`
- `CompilerAdvice` → `def` (post-expansion)

Note: Some forms like `defmulti` expand to `let` blocks, not bare `def`, so they only appear in raw.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     analyze-ns-complete                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────┐    ┌──────────────────────┐          │
│  │   Raw Metrics        │    │   Expanded Metrics   │          │
│  │   (macroexpand hook) │    │   (analyzeSeq hook)  │          │
│  ├──────────────────────┤    ├──────────────────────┤          │
│  │ • MacroexpandAdvice  │    │ • CompilerAdvice     │          │
│  │ • phase="raw"        │    │ • phase="expanded"   │          │
│  │ • op=defn,defn-,...  │    │ • op=def             │          │
│  │ • countExpressions() │    │ • countExpressions() │          │
│  │ • maxDepth()         │    │ • maxDepth()         │          │
│  └──────────────────────┘    └──────────────────────┘          │
│                                                                  │
│  ┌──────────────────────────────────────────────────┐          │
│  │   Unused Detection (hook registerVar)            │          │
│  │   • Track defs from analyzeSeq                   │          │
│  │   • Track refs from registerVar                  │          │
│  │   • unused = defs - refs                         │          │
│  └──────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Enhance CompilerAdvice (Expanded Metrics)

### Add to CompilerAdvice.java

```java
/**
 * Count expressions in a form recursively.
 */
public static int countExpressions(Object form) {
    if (form == null) return 0;
    try {
        Class<?> iseqClass = Class.forName("clojure.lang.ISeq");
        if (iseqClass.isInstance(form)) {
            int count = 1;
            Object current = form;
            while (current != null) {
                Object first = current.getClass().getMethod("first").invoke(current);
                count += countExpressions(first);
                current = current.getClass().getMethod("next").invoke(current);
            }
            return count;
        }
        // Handle vectors, maps, sets similarly...
    } catch (Exception e) {}
    return 1;
}

/**
 * Calculate max nesting depth.
 */
public static int maxDepth(Object form) {
    // Similar recursive walk, track max depth
}

/**
 * Get LINE_BEFORE/AFTER for accurate LOC.
 */
public static int[] getLineRange() {
    // Read Compiler.LINE_BEFORE and LINE_AFTER vars
}
```

### Update captureForm()

```java
// In captureForm(), after getting name/ns/line:
int[] lineRange = getLineRange();
if (lineRange != null) {
    defInfo.put("line", lineRange[0]);
    defInfo.put("end-line", lineRange[1]);
    defInfo.put("loc", lineRange[1] - lineRange[0] + 1);
}

// Get the init form (body of def)
Object rest = form.getClass().getMethod("next").invoke(form);  // skip 'def'
rest = rest.getClass().getMethod("next").invoke(rest);          // skip name
Object initForm = rest.getClass().getMethod("first").invoke(rest);

defInfo.put("expressions-expanded", countExpressions(initForm));
defInfo.put("max-depth-expanded", maxDepth(initForm));
```

---

## Phase 2: Add Var Reference Tracking

### Create VarRefAdvice.java

```java
public class VarRefAdvice {
    public static final ConcurrentHashMap<String, Set<String>> NS_REFS =
        new ConcurrentHashMap<>();

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object var) {
        try {
            String ns = getCurrentNamespace();
            Object sym = var.getClass().getMethod("toSymbol").invoke(var);
            NS_REFS.computeIfAbsent(ns, k -> ConcurrentHashMap.newKeySet())
                   .add(sym.toString());
        } catch (Exception e) {}
    }
}
```

### Update MetricsAgent.java

```java
// Hook registerVar for unused detection
.type(ElementMatchers.named("clojure.lang.Compiler"))
.transform((builder, ...) ->
    builder.visit(Advice.to(VarRefAdvice.class)
        .on(ElementMatchers.named("registerVar")
            .and(ElementMatchers.isPrivate())
            .and(ElementMatchers.isStatic()))))
```

---

## Phase 3: Create VarRefBridge.java

```java
public class VarRefBridge {
    // Mirror of VarRefAdvice.NS_REFS for Clojure access

    public static Map<String, Set<String>> getRefsPerNamespace() {
        return new HashMap<>(VarRefAdvice.NS_REFS);
    }

    public static void clear() {
        VarRefAdvice.NS_REFS.clear();
    }
}
```

---

## Phase 4: Update Clojure analyze.clj

### New function: analyze-ns-via-hook

```clojure
(defn analyze-ns-via-hook
  "Analyze namespace using compiler hook (no tools.analyzer)."
  [ns-sym]
  (agent/clear!)
  (VarRefBridge/clear)

  (require ns-sym :reload)

  (let [captured (agent/get-captured-defs)
        refs-map (into {} (VarRefBridge/getRefsPerNamespace))]
    (mapv (fn [{:keys [name ns line end-line loc
                       expressions-expanded max-depth-expanded]}]
            (let [;; Get raw metrics from source
                  source-form (read-def-from-source ns name)
                  refs (get refs-map ns #{})]
              {:name name
               :ns ns
               :line line
               :metrics {:loc loc
                         :expressions-raw (count-sexp-forms source-form)
                         :expressions-expanded expressions-expanded
                         :max-depth-raw (sexp-max-depth source-form)
                         :max-depth-expanded max-depth-expanded
                         :unused? (not (contains? refs (str ns "/" name)))}}))
          captured)))
```

### Keep existing raw metric functions

The existing `count-sexp-forms` and `sexp-max-depth` work perfectly for raw metrics - they read and walk source directly.

---

## Phase 5: Remove tools.analyzer Dependency

### Update deps.edn

```clojure
;; Remove:
;; org.clojure/tools.analyzer.jvm {:mvn/version "1.3.2"}
```

### Update analyze.clj

- Remove `(:require [clojure.tools.analyzer.jvm :as ana.jvm] ...)`
- Remove all `ana.jvm/...` calls
- Replace `analyze-ns` with `analyze-ns-via-hook`

---

## Files to Modify

| File | Changes |
|------|---------|
| `CompilerAdvice.java` | Add countExpressions, maxDepth, getLineRange |
| `MetricsBridge.java` | Update capture format with new fields |
| `VarRefAdvice.java` | NEW: Hook registerVar for var tracking |
| `VarRefBridge.java` | NEW: Clojure-accessible ref data |
| `MetricsAgent.java` | Add registerVar hook |
| `agent.clj` | Add var ref functions |
| `analyze.clj` | Replace tools.analyzer with hook-based analysis |
| `deps.edn` | Remove tools.analyzer.jvm dependency |

---

## Output Format (unchanged)

```clojure
{:name "my-fn"
 :ns "my.namespace"
 :line 42
 :metrics {:loc 15
           :expressions-raw 12
           :expressions-expanded 28
           :max-depth-raw 2
           :max-depth-expanded 4
           :unused? false}}
```

---

## Verification Plan

### Step 1: Compare Results

```clojure
;; Run both approaches on same namespace
(def analyzer-result (analyze-ns 'test.fixtures.alpha))
(def hook-result (analyze-ns-via-hook 'test.fixtures.alpha))

;; Compare metrics
(= (set (map :name analyzer-result))
   (set (map :name hook-result)))

(doseq [[a h] (map vector
                   (sort-by :name analyzer-result)
                   (sort-by :name hook-result))]
  (when (not= (:metrics a) (:metrics h))
    (println "Mismatch:" (:name a) (:metrics a) (:metrics h))))
```

### Step 2: Test Edge Cases

- Macros (defmacro)
- Protocols (defprotocol)
- Multimethods (defmulti/defmethod)
- Records (defrecord)
- Threading macros (-> ->>)

### Step 3: Run Existing Tests

```bash
clj -X:test
```

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Expression count differs | Hook sees post-expansion; document the difference |
| Missing defs | Ensure depth=0 filter catches all top-level defs |
| Var refs incomplete | registerVar may miss some; compare with analyzer first |
| Performance regression | Hook is faster than tools.analyzer |

---

## Timeline

| Phase | Task | Estimate |
|-------|------|----------|
| 1 | Enhance CompilerAdvice (expressions, depth, LOC) | 1 day |
| 2 | Add VarRefAdvice + Bridge | 1 day |
| 3 | Update agent.clj with new functions | 0.5 day |
| 4 | Parallel testing (compare both approaches) | 1 day |
| 5 | Update analyze.clj, remove tools.analyzer | 0.5 day |
| 6 | Final testing and documentation | 1 day |
| **Total** | | **5 days** |

---

## Appendix: Key Compiler Locations

From `/tmp/clojure-src/src/jvm/clojure/lang/Compiler.java`:

| Function | Line | Purpose |
|----------|------|---------|
| `macroexpand` | 7091 | Full macro expansion (raw hook point) |
| `macroexpand1` | 6994 | Single macro expansion step |
| `analyzeSeq` | 7098 | Seq analysis (expanded hook point) |
| `compile1` | 7720 | Compilation entry - calls macroexpand before analyze |
| `DefExpr.Parser.parse` | 529 | Parses def forms |
| `FnExpr.parse` | 3973 | Parses fn forms |
| `analyzeSymbol` | 7308 | Resolves symbols to VarExpr |
| `registerVar` | 7522 | Tracks var references |
| `load` | 7618 | Top-level file loading |
| `LINE_BEFORE/AFTER` | 314-316 | Thread-bound line tracking |

## Appendix: Current PoC Files

| File | Purpose |
|------|---------|
| `MacroexpandAdvice.java` | Hooks `macroexpand`, captures with `phase="raw"` |
| `CompilerAdvice.java` | Hooks `analyzeSeq`, captures with `phase="expanded"` |
| `MetricsBridge.java` | Thread-safe buffer for captured defs |
| `MetricsAgent.java` | ByteBuddy agent entry point, installs both hooks |
