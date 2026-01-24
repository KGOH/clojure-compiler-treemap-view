# Purity Score Metric

> **Note:** This research was written for tools.analyzer AST. Needs adaptation for compiler hooks (working with raw/expanded s-expressions instead of AST nodes).

Research notes for adding a purity/side-effect metric to the treemap visualization.

## Goal

Detect truly pure functions (only data transformation, no effects) vs impure functions (I/O, mutation, Java interop).

In a DI-heavy codebase like gmonit/collector:
- **Handlers are pure** - they receive functions as arguments and call them
- **Adapters are impure** - they contain actual Redis/ClickHouse/HTTP calls

This is the "functional core, imperative shell" pattern.

---

## AST Op Classification

From tools.analyzer and tools.analyzer.jvm, there are exactly 46 AST op types.

### Pure Ops (Safe - just data)

```clojure
(def pure-ops
  "AST ops that are inherently pure - they just construct or bind data"
  #{:const :local :var :let :letfn :if :do :loop :recur
    :fn :fn-method :binding
    :map :vector :set :quote :with-meta
    :case :case-test :case-then :the-var
    :instance?
    :static-field :instance-field  ; field READ is pure
    :keyword-invoke})              ; (:key m) is just lookup
```

### Impure Ops (Definitely side-effectful)

```clojure
(def impure-ops
  "AST ops that definitely have side effects"
  #{:set!              ; mutation
    :def               ; namespace mutation
    :throw             ; control flow effect
    :monitor-enter     ; synchronization
    :monitor-exit
    :import            ; namespace mutation
    :deftype           ; class generation
    :reify})
```

### Unknown Purity Ops (Depends on what's called)

```clojure
(def call-ops
  "AST ops where purity depends on the target"
  #{:invoke            ; (f x) - depends on f
    :static-call       ; (Math/abs x) - depends on method
    :instance-call     ; (.toString x) - depends on method
    :new               ; (Date.) - depends on constructor
    :protocol-invoke   ; depends on implementation
    :prim-invoke       ; depends on target
    :host-interop})    ; unresolved - flag it
```

---

## Known Pure Core Functions

Whitelist of `clojure.core` functions that are pure:

```clojure
(def pure-core-fns
  "clojure.core functions known to be pure"
  '#{;; Sequence operations
     map filter reduce mapv filterv into
     take drop take-while drop-while
     partition partition-by partition-all group-by
     sort sort-by reverse
     first rest next second last butlast
     cons concat

     ;; Collection operations
     assoc dissoc get get-in assoc-in update update-in
     conj merge merge-with select-keys
     keys vals zipmap
     count empty? not-empty

     ;; Predicates
     seq? coll? vector? map? set? list? keyword? symbol? string? number?
     nil? some? any? true? false?
     = not= < > <= >= compare

     ;; Arithmetic
     + - * / inc dec mod rem quot
     min max abs

     ;; String/keyword/symbol
     str subs name keyword symbol namespace

     ;; Higher-order
     identity constantly partial comp juxt apply

     ;; Constructors
     hash-map hash-set vector list vec set

     ;; Logic
     and or not

     ;; ... extend as needed
     })
```

---

## Detection Strategy

### Whitelist Approach (Recommended)

1. **Safe ops** - known pure, no flags
2. **Impure ops** - always flag
3. **Call ops** - check against `pure-core-fns` whitelist, flag unknowns

```clojure
(defn analyze-purity [ast]
  (let [issues (atom [])]
    (ast/postwalk
     ast
     (fn [node]
       (let [op (:op node)]
         (cond
           ;; Inherently unsafe
           (impure-ops op)
           (swap! issues conj {:op op :reason :impure-op})

           ;; Invoke - check what's being called
           (= op :invoke)
           (let [fn-var (-> node :fn :var)]
             (when-not (and fn-var
                            (pure-core-fns (symbol (name (.sym fn-var)))))
               (swap! issues conj {:op op :reason :unknown-invoke :var fn-var})))

           ;; Java interop - flag all
           (#{:static-call :instance-call :new :host-interop} op)
           (swap! issues conj {:op op :reason :java-interop})

           ;; Safe ops - no action
           (pure-ops op) nil

           ;; Unknown op - flag
           :else
           (swap! issues conj {:op op :reason :unknown-op})))
       node))
    @issues))
```

---

## Metric Output

Instead of binary pure/impure, use counts:

```clojure
{:pure-ops 45           ; known-pure structural ops
 :pure-invokes 12       ; calls to known-pure fns (map, filter, etc.)
 :unknown-invokes 3     ; calls to unanalyzed functions
 :java-interop 1        ; .method calls, new, static calls
 :impure-ops 0}         ; set!, throw, etc.
```

For treemap visualization, a single score:
```clojure
:side-effects-total (+ unknown-invokes java-interop impure-ops)
```

---

## Expected Results for gmonit/collector

| Namespace | Side Effects | Reason |
|-----------|--------------|--------|
| `gmonit.common.adapters.clickhouse` | HIGH | `HttpURLConnection`, `.getOutputStream` |
| `gmonit.common.adapters.redis` | HIGH | `JedisPooled`, `.append`, `.expire` |
| `gmonit.distributed-tracing.state` | HIGH | `.append`, `.get` on Jedis |
| `gmonit.apm.methods.metric-data` | LOW/ZERO | Calls injected `insert-os` (local invoke) |
| `gmonit.apm.methods.error-data` | LOW/ZERO | Calls injected `insert` |

The DI pattern means handlers only see local function calls, which count as `unknown-invokes` (low weight). Adapters have direct Java interop (high weight).

---

## Edge Cases

### False Positives (pure code marked impure)
- `(-> "resource.txt" io/resource slurp)` - reads classpath resource, technically I/O
- Calls to pure library functions not in whitelist

### False Negatives (impure code marked pure)
- Impurity hidden in helper functions (would need transitive analysis)
- `clj-http.client/request` if not specifically blacklisted

### Mitigations
- Extend `pure-core-fns` with common pure library functions
- Add explicit blacklist for known-impure library functions
- Consider transitive purity analysis (propagate through call graph)

---

## Implementation Notes

- tools.analyzer does NOT have built-in purity analysis
- `:children` field on each node tells which keys contain child nodes
- Use `clojure.tools.analyzer.ast/postwalk` for traversal
- `:keyword-invoke` (`:key m`) is pure - just lookup
- Field access (`:static-field`, `:instance-field`) is pure; only `:set!` is impure

---

## References

- `/Users/kgofhedgehogs/Work/tools.analyzer/spec/ast-ref.edn` - base op spec
- `/Users/kgofhedgehogs/Work/tools.analyzer.jvm/spec/ast-ref.edn` - JVM op spec
- `clojure.tools.analyzer.ast` - AST walking utilities
