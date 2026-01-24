# Analyzer Migration: tools.analyzer.jvm → Compiler Hook

## Current State (2025-01)

### Completed PoCs

1. **Unified Compiler Hook (macroexpand)** - Working
   - Single ByteBuddy hook on `Compiler.macroexpand` with enter/exit
   - `OnMethodEnter` captures raw form (pre-expansion)
   - `OnMethodExit` captures expanded form (post-expansion, if changed)
   - Java captures minimal data: form object, phase, ns, compiler-line
   - Clojure extracts: op, name, line, end-line from form metadata
   - Files: `MacroexpandUnifiedAdvice.java`, `MetricsBridge.java`

2. **Class Loader Hook** - Working
   - Captures all loaded classes with bytecode size
   - Filters JDK classes
   - Files: `ClassLoaderTransformer.java`, `ClassLoadBridge.java`

3. **Unused Var Detection (VarExpr Hook)** - Working ✓
   - Hooks `Compiler$VarExpr` and `Compiler$TheVarExpr` constructors
   - Captures all var references during compilation
   - Compares with `ns-interns` to find unused vars
   - **Verified**: Matches tools.analyzer results exactly
   - Files: `VarRefAdvice.java`, `VarRefBridge.java`

---

## Unused Detection Implementation

### Approach: Hook VarExpr Constructor

The Clojure compiler resolves symbols to either:
- `LocalBindingExpr` - local bindings (let, fn params, etc.)
- `VarExpr` - var references

By hooking `VarExpr` constructor, we capture ONLY var references (locals excluded by compiler).

### Files Added

```
metrics-agent/src/main/java/clojure/metrics/
├── VarRefAdvice.java     # ByteBuddy advice for VarExpr/TheVarExpr
└── VarRefBridge.java     # Thread-safe storage for var references
```

### Algorithm

```
unused = (ns-interns loaded-namespaces) - (var-references from hooks)
```

### Clojure API

```clojure
(require '[clojure-compiler-treemap-view.agent :as agent])

;; Load namespaces with capturing
(agent/capture-namespaces '[my.ns1 my.ns2])

;; Find unused vars
(agent/find-unused-vars '[my.ns1 my.ns2])
;; => #{"my.ns1/unused-fn" "my.ns2/also-unused"}

;; Compare with tools.analyzer (verification)
(agent/compare-unused-detection '[my.namespace])
;; => {:hook-unused #{...}, :analyzer-unused #{...}, :match? true}
```

### Verification Results

Tested on real namespaces - hook-based detection matches tools.analyzer exactly:

```
analyze namespace:  Hook=8, Analyzer=8, Match=true
core namespace:     Hook=2, Analyzer=2, Match=true
```

---

## Metrics Computation (confirmed approach)

Per systems-engineer advice, compute metrics in Clojure (not Java):

```clojure
;; From captured raw form
:expressions-raw (count-sexp-forms raw-form)
:max-depth-raw (sexp-max-depth raw-form)

;; From captured expanded form
:expressions-expanded (count-sexp-forms expanded-form)
:max-depth-expanded (sexp-max-depth expanded-form)

;; LOC from line metadata
:loc (if (and line end-line) (inc (- end-line line)) 1)
```

The existing `count-sexp-forms` and `sexp-max-depth` functions work on any form.

---

## Migration Phases (after PoC complete)

### Phase 1: Parallel Implementation

- Create `analyze-ns-via-hook` alongside existing `analyze-ns`
- Both compute same metrics format
- Add comparison function

### Phase 2: Verification

- Run both on test fixtures, verify metrics match
- Document expected differences (raw vs expanded counts may differ)
- Test on real codebases

### Phase 3: Switch Default

```clojure
(defn analyze-ns [ns-sym]
  (if (agent/agent-available?)
    (analyze-ns-via-hook ns-sym)
    (analyze-ns-via-tools-analyzer ns-sym)))
```

### Phase 4: Remove Dependency

- Remove tools.analyzer.jvm from deps.edn
- Remove skip-namespace workarounds
- Simplify error handling

---

## Files Overview

### Current Agent Files

```
metrics-agent/src/main/java/clojure/metrics/
├── MacroexpandUnifiedAdvice.java  # Def form capture
├── MetricsBridge.java             # Thread-safe buffer for defs
├── VarRefAdvice.java              # Var reference capture
├── VarRefBridge.java              # Thread-safe storage for refs
├── MetricsAgent.java              # ByteBuddy agent entry point
├── ClassLoaderTransformer.java    # Class loading capture
└── ClassLoadBridge.java           # Class data access
```

### Files to Modify for Migration

| File | Changes |
|------|---------|
| `analyze.clj` | Add `analyze-ns-via-hook`, integrate unused from agent |
| `deps.edn` | Remove tools.analyzer.jvm (Phase 4) |

---

## Next Action

**Implement `analyze-ns-via-hook`**

1. Use captured forms from `agent/get-captured-defs`
2. Compute metrics using `count-sexp-forms` and `sexp-max-depth`
3. Get unused vars from `agent/find-unused-vars`
4. Return same format as `analyze-ns`
5. Compare results with existing implementation
