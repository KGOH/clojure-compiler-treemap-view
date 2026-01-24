# Fan-in Metrics

> **Note:** This research was written for tools.analyzer AST. Needs adaptation for compiler hooks (using VarExpr hook data instead of AST traversal).

Research notes for adding fan-in (incoming dependency) metrics to the treemap visualization.

## Goal

Measure how "central" a function is - how many other parts of the codebase depend on it.

This is the **inverse** of locality metrics:
- Locality: "What does this function call?" (outgoing)
- Fan-in: "Who calls this function?" (incoming)

---

## Metric Definition

**Fan-in**: Count of unique callers referencing this var, excluding self-namespace.

Two levels (matching locality metrics pattern):

```clojure
:fan-in-ns-count   ; unique namespaces calling this function
:fan-in-fn-count   ; unique functions calling this function
```

---

## Why "Fan-in"?

Graph theory terminology - "fan-in" is the number of incoming edges to a node.

| Candidate | Issue |
|-----------|-------|
| `:popularity` | Vague, sounds social |
| `:dependents` | Awkward plural |
| `:callers` | Implies runtime behavior |
| **`:fan-in`** | Precise, unambiguous |

---

## Why Both Namespace and Function Level?

The ratio `fan-in-fn / fan-in-ns` reveals different patterns:

### Example Scenarios

```clojure
;; Case A: Domain utility
;; foo.utils/format-date called by:
;;   bar.api/handler, bar.api/other-handler, bar.api/third-handler
:fan-in-ns-count 1
:fan-in-fn-count 3
;; Ratio: 3.0 → Heavily used within one area

;; Case B: Cross-cutting utility
;; foo.utils/validate called by:
;;   bar.api/handler, baz.core/process, qux.web/render
:fan-in-ns-count 3
:fan-in-fn-count 3
;; Ratio: 1.0 → Used once per namespace

;; Case C: Problematic coupling
;; foo.utils/do-everything called from 5 namespaces, 20 functions
:fan-in-ns-count 5
:fan-in-fn-count 20
;; Ratio: 4.0 → Heavy cross-cutting usage
```

### Pattern Interpretation

| fan-in-ns | fan-in-fn | Ratio | Meaning |
|-----------|-----------|-------|---------|
| Low | High | High | Domain-specific utility (good cohesion) |
| High | ~Same | ~1.0 | True cross-cutting utility |
| High | Much higher | High | Potential god function (coupling risk) |

---

## Metric Schema

```clojure
{:fn-name "foo.utils/format-date"

 ;; Aggregates for treemap size/color
 :fan-in-ns-count 3
 :fan-in-fn-count 8

 ;; Detail for tooltips
 :fan-in-callers {"bar.api" ["handler" "process"]
                  "baz.core" ["main" "init" "run"]
                  "qux.web" ["render" "show" "display"]}}
```

---

## Relationship to Dead Code Detection

| fan-in-ns | internal refs | Meaning |
|-----------|---------------|---------|
| 0 | 0 | Dead code |
| 0 | >0 | Private helper (localized, safe to modify) |
| 1 | any | Single external user (inline candidate?) |
| high | any | Core utility (change with extreme care) |

Fan-in adds granularity beyond binary dead/alive.

---

## Visualization Interpretation

**Inverted from other metrics:**
- High depth/complexity = usually bad
- High fan-in = not bad, just central/important

| fan-in | Meaning |
|--------|---------|
| 0 | Entry point, dead code, or private helper |
| Low | Localized function, low change impact |
| High | Core utility, many dependents, high change impact |

### Color Interpretation
- **Blue (low)**: Safe to modify, few dependents
- **Red (high)**: Central function, modify with care

---

## Edge Cases (Handled Naturally)

| Case | fan-in | Notes |
|------|--------|-------|
| HTTP handler (entry point) | 0 | Correct - called externally, not internally |
| `defn-` private function | 0 | Expected - private visibility |
| Test utility | low | Tests are separate namespaces |
| Protocol method | counts call sites | Implementations and calls both count |
| Multimethod | counts defmethods + calls | Each defmethod references the multimethod |

No special-casing needed.

---

## Implementation

Requires global analysis (all namespaces must be analyzed):

```clojure
(defn build-fan-in-map
  "Build map of var-symbol -> {calling-ns -> [calling-fns]}.
   Requires ASTs from ALL analyzed namespaces."
  [all-asts]
  (reduce
    (fn [acc {:keys [ns-sym fn-name ast]}]
      (let [var-refs (extract-var-refs (ast/nodes ast))]
        (reduce
          (fn [acc' var-sym]
            (let [var-ns (namespace var-sym)]
              (if (and var-ns (not= var-ns (str ns-sym)))
                (update-in acc' [var-sym (str ns-sym)]
                           (fnil conj []) (str fn-name))
                acc')))
          acc
          var-refs)))
    {}
    (flatten-to-fn-asts all-asts)))

(defn compute-fan-in-metrics [fan-in-map var-sym]
  (let [callers (get fan-in-map var-sym {})]
    {:fan-in-ns-count (count callers)
     :fan-in-fn-count (->> callers vals (map count) (reduce + 0))
     :fan-in-callers callers}))
```

---

## API

```clojure
(treemap! '[gmonit.apm.core ...]
          :project-prefix "gmonit."
          :fan-in? true           ; opt-in (requires global analysis)
          :size :fan-in-ns-count
          :color :fan-in-fn-count)
```

When `:fan-in?` is false (default), fan-in metrics are skipped.

---

## Performance

This is inherently a **global metric** - must analyze ALL namespaces to know who calls what.

- Single pass over all AST nodes: O(n)
- Already doing this for dead code detection
- Cost paid at analysis time, not visualization time

---

## Don't Store Derived Ratios

Let users compute `(/ fan-in-fn-count fan-in-ns-count)` if needed.

Raw counts are more flexible and avoid maintenance burden of derived fields.

---

## Future Enhancements

### Cross-Module Fan-in

Could distinguish:
- Called from same module (`foo.*` calling `foo.utils`)
- Called from other modules (`bar.*` calling `foo.utils`)

Requires defining "module" - defer to later.

### Weighted Fan-in

Could weight by caller importance (recursive PageRank-style).
Over-engineering for v1.

---

## Summary

| Metric | Definition |
|--------|------------|
| `:fan-in-ns-count` | Unique namespaces calling this function |
| `:fan-in-fn-count` | Unique functions calling this function |
| `:fan-in-callers` | Detail map for tooltips |

Answers: "How central is this function? How risky is it to change?"
