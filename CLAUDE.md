# Treemap - Clojure Code Metrics Visualization

Analyze Clojure namespaces and generate interactive D3.js treemap visualizations of code metrics.

## Quick Start

```clojure
(require '[treemap.core :as treemap])
(treemap/treemap! '[my.namespace])           ; opens browser
(treemap/treemap! '[ns1 ns2] :size :loc :color :max-depth-expanded)
```

## Architecture

```
analyze-ns -> analyze-nses -> build-hierarchy -> render-html -> open-html
     |             |               |                 |
  AST parse    unused detect   flat->tree      inject into template
```

**Core files**:
- `src/treemap/analyze.clj` - AST analysis via tools.analyzer.jvm, metrics extraction
- `src/treemap/core.clj` - HTML generation, public API
- `resources/treemap.{html,css,js}` - D3.js visualization

## Key Functions

### analyze.clj

- `analyze-ns` - Returns `{:analyzed [...] :asts [...]}`. Caches ASTs for reuse in unused detection.
- `analyze-nses` - Aggregates namespaces, runs `find-unused-vars`, adds `:unused?` flag.
- `build-hierarchy` - Converts flat fn-data to D3-compatible nested tree.
- `def-metrics` - Extracts metrics from single `:def` AST node.

### core.clj

- `treemap!` - One-liner: analyze -> hierarchy -> render -> open browser.
- `render-html` - Injects data + options into HTML template.

## Metrics

| Metric | Description |
|--------|-------------|
| `:loc` | Lines of code |
| `:expressions-raw` | Form count from source (pre-macro-expansion) |
| `:expressions-expanded` | AST node count (post-macro-expansion) |
| `:max-depth-raw` | Nesting depth from source |
| `:max-depth-expanded` | Nesting depth from AST |
| `:unused?` | True if var is defined but never referenced |

Raw vs expanded: Threading macros like `->` appear flat in raw metrics but nested in expanded.

## Data Flow

```clojure
;; analyze-ns output
{:analyzed [{:name "my-fn"
             :ns "my.namespace"
             :file "..."
             :line 42
             :metrics {:loc 5
                       :expressions-raw 12
                       :expressions-expanded 28
                       :max-depth-raw 2
                       :max-depth-expanded 4}}]
 :asts [...]}  ; raw ASTs for unused detection

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
- `analyze/reset-analyzer!` - Fix protocol cache issues (call if you get reflection errors)
- Failed forms included with `:failed? true` in metrics (uses reader fallback)

## Testing

```bash
clj -X:test
```

Test fixtures in `test/treemap/fixtures/` - namespaces with known characteristics for assertions.

## Notes Directory

`notes/` contains research docs for potential future metrics (purity, locality, fan-in, java interop). Design exploration only, not implementation.

## Dependencies

- `tools.analyzer.jvm` - AST analysis
- `jsonista` - JSON serialization for D3

## Gotchas

1. **Skip list**: Some namespaces break the analyzer (clojure.tools.analyzer.*, clojure.spec.*, clojure.reflect). These use reader fallback with partial metrics.

2. **Raw forms**: `macro-expanded-ops` in analyze.clj defines which AST ops use `:raw-forms`. Only `:invoke`, `:static-call`, `:instance-call`, `:do` - others don't carry meaningful raw forms.

3. **AST caching**: `analyze-ns` returns ASTs separately so `analyze-nses` can run cross-namespace unused detection without re-analyzing.
