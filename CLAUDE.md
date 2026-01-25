# clojure-compiler-treemap-view - Clojure Code Metrics Visualization

Analyze Clojure namespaces and generate interactive D3.js treemap visualizations of code metrics.

## Quick Start

```bash
# Start REPL with metrics agent (required)
clj -M:agent
```

```clojure
(require '[clojure-compiler-treemap-view.core :as cctv])
(cctv/treemap! '[my.namespace])           ; opens browser
(cctv/treemap! '[ns1 ns2] :size :expressions-raw :color :max-depth-expanded)
```

## Architecture

```
analyze-ns -> analyze-nses -> build-hierarchy -> render-html -> open-html
     |             |               |                 |
hook capture   unused detect   flat->tree      inject into template
```

**Core files**:
- `src/clojure_compiler_treemap_view/agent.clj` - Java agent wrapper, compiler hook interface
- `src/clojure_compiler_treemap_view/analyze.clj` - Metrics extraction from captured forms
- `src/clojure_compiler_treemap_view/core.clj` - HTML generation, public API
- `resources/treemap.{html,css,js}` - D3.js visualization

## Key Functions

### agent.clj

- `get-captured-defs` - Drain captured def forms from agent buffer
- `find-unused-vars` - Find vars defined but never referenced via VarRefBridge
- `clear!`, `clear-var-references!` - Reset capture buffers

### analyze.clj

- `analyze-ns` - Clears buffer, reloads namespace, processes captured defs. Returns `{:analyzed [...] :asts []}`.
- `analyze-nses` - Aggregates namespaces, runs `find-unused-vars`, adds `:unused?` flag.
- `build-hierarchy` - Converts flat fn-data to D3-compatible nested tree.
- `count-sexp-forms`, `sexp-max-depth` - S-expression metrics

### core.clj

- `treemap!` - One-liner: analyze -> hierarchy -> render -> open browser.
- `render-html` - Injects data + options into HTML template.

## Metrics

| Metric | Description |
|--------|-------------|
| `:expressions-raw` | Form count from source (pre-macro-expansion) |
| `:expressions-expanded` | Form count after macro-expansion |
| `:max-depth-raw` | Nesting depth from source |
| `:max-depth-expanded` | Nesting depth after macro-expansion |
| `:unused?` | True if var is defined but never referenced |

Raw vs expanded: Threading macros like `->` appear flat in raw metrics but nested in expanded.

## Data Flow

```clojure
;; analyze-ns output
{:analyzed [{:name "my-fn"
             :ns "my.namespace"
             :file nil   ; hooks don't capture file path
             :line 42
             :metrics {:expressions-raw 12
                       :expressions-expanded 28
                       :max-depth-raw 2
                       :max-depth-expanded 4}}]
 :asts []}  ; always empty (API compatibility)

;; build-hierarchy output (D3-compatible)
{:name "root"
 :children [{:name "my"
             :children [{:name "namespace"
                         :children [{:name "my-fn"
                                     :ns "my.namespace"
                                     :metrics {...}}]}]}]}
```

## Error Handling

- `analyze/errors` - Atom with analysis failures `[{:ns :phase :error :message :timestamp}]`
- `analyze/clear-errors!` - Reset error log
- Namespaces that fail to load will have empty results (no partial fallback)

## Testing

```bash
clj -M:agent:test:runner
```

Test fixtures in `test/clojure_compiler_treemap_view/fixtures/` - namespaces with known characteristics for assertions.

## Notes Directory

`notes/` contains research docs for potential future metrics (purity, locality, fan-in, java interop). Design exploration only, not implementation.

## Dependencies

- Java metrics agent (via -javaagent flag)
- `jsonista` - JSON serialization for D3

## Agent Requirement

The metrics agent must be loaded for this library to work. Use the `:agent` alias:

```bash
clj -M:agent
```

The agent instruments the Clojure compiler to capture:
1. **MetricsBridge** - def forms before and after macro expansion
2. **VarRefBridge** - var references for unused detection
3. **ClassLoadBridge** - bytecode sizes for runtime footprint analysis

## Development Best Practices

Learnings from migrating tools.analyzer.jvm to compiler hooks.

### Migration Strategy

- **Build PoC alongside existing code** - Never break working functionality during exploration
- **Verify output equivalence before switching** - Write comparison functions (e.g., `compare-with-analyzer`) to confirm new approach matches old
- **Only migrate after verification passes** - PoC must be proven correct before replacing the original

### Java/Clojure Boundary Design

- **Minimal Java hooks, defer processing to Clojure** - Java captures raw data (form, phase, ns, line); Clojure extracts names, computes metrics
- **Pass Clojure data structures directly** - Don't serialize/deserialize at the boundary; just pass the form object
- **Thread-local state for context** - Capture thread-bound values (namespace, line) at method entry before they change

### Avoiding Complexity

- **Skip graceful degradation** - If a dependency (like the agent) is required, require it; optional loading adds complexity without proportional value
- **Use direct imports once committed** - Reflection-based optional loading is a migration aid, not a permanent solution
- **Extract shared logic early** - Functions like `process-captured-defs` and `add-unused-flags` prevent duplication between single-ns and multi-ns paths

### What to Avoid

- **Complex extraction in Java hooks** - Parsing metadata, computing metrics, etc. belongs in Clojure where it's easier to iterate
- **Skip lists and fallback paths** - If the approach needs a skip list, the approach has a problem (hooks solved this)
- **Heavy transitive dependencies** - tools.analyzer.jvm pulled in significant deps; hooks have zero runtime deps

### Testing Approach

- **Test output format, not implementation** - Tests should verify shape of results, not how they were computed
- **Fixture namespaces with known characteristics** - Test against namespaces designed to exercise specific cases
- **Comparison tests during migration** - Temporarily keep both implementations and assert equivalence

### Change Impact Awareness

- **Trace dependencies through the system** - A new runtime requirement affects CI, docs, and user setup
- **Check CI/CD when changing runtime requirements** - If tests need an agent, CI needs to build and load it too
- **Update all entry points** - deps.edn aliases, CI workflows, README quickstart must stay in sync
