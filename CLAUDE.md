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
(cctv/treemap! '[ns1 ns2] :size :loc :color :max-depth-expanded)
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
| `:loc` | Lines of code |
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
             :metrics {:loc 5
                       :expressions-raw 12
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
clj -M:agent -X:test
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
