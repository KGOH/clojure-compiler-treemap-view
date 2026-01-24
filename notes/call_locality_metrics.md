# Call Locality Metrics

> **Note:** This research was written for tools.analyzer AST. Needs adaptation for compiler hooks (extracting var refs from s-expressions instead of AST nodes).

Research notes for adding call locality metrics to the treemap visualization.

## Goal

Measure how "local" a function's dependencies are:
- Does it only use functions from its own namespace and clojure.core?
- Does it reach into other project namespaces (entanglement)?
- Does it use external libraries?

---

## Two Dimensions: Namespaces vs Functions

### Why both?

Namespace count alone conflates different situations:

```clojure
;; Shallow orchestrator: ns=4, fn=4, ratio=1.0
(defn process-order [order]
  (validate/check order)
  (inventory/reserve (:items order))
  (payment/charge (:total order))
  (email/send-confirmation order))

;; Deep specialist: ns=1, fn=6, ratio=6.0
(defn transform-data [s]
  (-> s str/trim str/lower-case str/split
      first (str/replace #"-" "_") str/upper-case))
```

- **Orchestrator** - coordination point, changing requires business understanding
- **Specialist** - deep coupling to one library, changing that lib touches many call sites

---

## Two Categories: Library vs Entanglement

### Library Usage
External dependencies (not the project's own code):
- `clojure.string`, `clojure.set` - stdlib extensions
- `medley.core`, `jsonista.core` - third-party libs

### Entanglement
Internal cross-namespace coupling within the project:
- `foo.bar` using `foo.baz` → entangled
- `foo.bar` using `foo.bar.baz` → also entangled (nested is not special)
- `foo.core` using `foo.bar.baz` → entangled

### Baseline (Zero Cost)
- Same namespace (self-references)
- `clojure.core` - implicit, always available

---

## Metric Schema

```clojure
{:fn-name "my-ns/process"

 ;; Aggregates for treemap size/color
 :library-ns-count 2        ; unique library namespaces
 :library-fn-count 8        ; unique library functions
 :entanglement-ns-count 3   ; unique project namespaces (not self)
 :entanglement-fn-count 5   ; unique project functions

 ;; Detail for tooltips
 :library-calls {"clojure.string" ["join" "trim" "split"]
                 "medley.core" ["map-vals"]}
 :entangled-calls {"foo.utils" ["helper"]
                   "foo.db" ["query!" "insert!"]}}
```

---

## Coupling Depth Ratio

`fn-count / ns-count` = coupling depth

| Ratio | Interpretation |
|-------|----------------|
| ~1.0 | Facade/orchestrator - touches many things lightly |
| 2-3 | Normal usage |
| >5 | Deep dependency on specific namespaces |

**High ratio on library deps**: Usually fine (deep clojure.string usage is idiomatic)

**High ratio on entangled deps**: Potential concern (tight coupling to internal module)

---

## Visualization Combinations

| Size by | Color by | Small+Hot | Large+Hot |
|---------|----------|-----------|-----------|
| ns-count | fn-count | Few deps, deep usage (specialist) | Many deps, extensive usage (mess) |
| fn-count | ns-count | Focused helper | Broad orchestrator |
| entanglement-ns | library-fn | Internal module, heavy lib usage | Cross-cutting concern |

---

## Project Classification

Use prefix-based detection:

```clojure
(defn project-namespace? [ns-str project-prefix]
  (str/starts-with? ns-str project-prefix))

;; Usage
(project-namespace? "gmonit.apm.core" "gmonit.") ;=> true (project)
(project-namespace? "clojure.string" "gmonit.")  ;=> false (library)
```

User specifies their project prefix (e.g., `"gmonit."`).

---

## Detection from AST

Extract from `:var` and `:the-var` nodes:

```clojure
(defn collect-deps [var-refs current-ns project-prefix]
  (let [current-ns-str (str current-ns)]
    (reduce
     (fn [acc v]
       (let [ns-str (namespace (.sym v))
             fn-str (name (.sym v))]
         (cond
           ;; Skip clojure.core and self
           (or (nil? ns-str)
               (= ns-str "clojure.core")
               (= ns-str current-ns-str))
           acc

           ;; Library
           (not (str/starts-with? ns-str project-prefix))
           (update-in acc [:library ns-str] (fnil conj []) fn-str)

           ;; Entanglement (project, not self)
           :else
           (update-in acc [:entangled ns-str] (fnil conj []) fn-str))))
     {:library {} :entangled {}}
     var-refs)))

(defn compute-counts [deps]
  {:library-ns-count (count (:library deps))
   :library-fn-count (->> (:library deps) vals (map count) (reduce + 0))
   :entanglement-ns-count (count (:entangled deps))
   :entanglement-fn-count (->> (:entangled deps) vals (map count) (reduce + 0))
   :library-calls (:library deps)
   :entangled-calls (:entangled deps)})
```

---

## API

```clojure
(treemap! '[gmonit.apm.core]
          :project-prefix "gmonit."  ;; enables locality metrics
          :size :entanglement-ns-count
          :color :library-fn-count)
```

When `:project-prefix` is nil (default), locality metrics are skipped.

---

## Interpretation Guide

| Pattern | Meaning |
|---------|---------|
| Low ns, low fn | Isolated leaf function, easy to test/move |
| Low ns, high fn | Specialist deeply using few libraries |
| High ns, low fn | Orchestrator touching many modules lightly |
| High ns, high fn | Complex function, potential refactoring target |

### Entanglement Concerns

High entanglement indicates:
- Hard to test in isolation
- Changing one module may break this function
- May indicate god-function that should be split
- Or legitimate coordination point

### Library Usage Concerns

High library usage is often fine:
- Utility functions naturally use clojure.string, etc.
- "Glue" functions integrate external concerns
- Only concerning if it creates upgrade friction

---

## Edge Cases

### Macro-expanded calls
`(-> x str/split first)` expands to show both `clojure.string/split` and `clojure.core/first`. This is correct - we measure real dependencies.

### Protocol calls
`:protocol-invoke` nodes may need special handling to extract protocol namespace.

### Java interop
`:static-call` and `:instance-call` don't have var refs. Tracked separately in purity metrics, not here.

### Transitive dependencies
We measure direct dependencies only. If `foo.core` calls `foo.utils/helper` which calls `clojure.string/join`, `foo.core` does NOT count `clojure.string` - only `foo.utils`.

---

## Unique vs Call Count

We count **unique functions**, not call frequency.

```clojure
;; Both count as library-fn-count = 1 for str/trim
(str/trim input)

(let [a (str/trim (:a data))
      b (str/trim (:b data))
      c (str/trim (:c data))] ...)
```

Rationale: We measure API surface area (what you need to understand), not repetition.

---

## Future Extensions

### Call frequency
Could add raw call count if needed for "how tightly bound to X" analysis.

### Weighted distance
Could weight by namespace hierarchy distance (same parent ns = closer). Deferred - adds complexity without clear benefit.

### Transitive analysis
Build call graph, propagate dependencies. Different feature, more complex.
