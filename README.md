# clj-compiler-view

Visualize Clojure codebase structure as an interactive treemap. See which functions are large, complex, or unused at a glance.

Heavily inspired by [Jonathan Blow's code metrics viewer](https://youtu.be/IdpD5QIVOKQ?t=1192) for the JAI programming language.

## Why Use This

- **See your codebase structure** - Namespaces become nested rectangles, functions become cells sized by complexity
- **Find refactoring targets** - Large red cells are complex functions that may need splitting
- **Detect dead code** - Unused vars are highlighted with a striped pattern
- **Compare source vs expansion** - Threading macros look simple in source but expand into deep nesting; see both views

## Installation

```clojure
;; deps.edn
{:deps {clj-compiler-view/clj-compiler-view {:local/root "path/to/clj-compiler-view"}}}
```

## Usage

```clojure
(require '[clj-compiler-view.core :as clj-compiler-view])

;; Analyze namespaces and open visualization in browser
(clj-compiler-view/treemap! '[my.app.core my.app.handlers])

;; Analyze all loaded namespaces
(clj-compiler-view/treemap! (->> (all-ns)
                       (map ns-name)))

;; Analyze all loaded namespaces matching a prefix
(clj-compiler-view/treemap! (->> (all-ns)
                       (map ns-name)
                       (filter #(str/starts-with? (str %) "my.app."))))
```

### Programmatic Use

```clojure
(require '[clj-compiler-view.analyze :as analyze])

;; Get raw analysis data
(def data (analyze/analyze-nses '[my.namespace]))

;; Build D3-compatible hierarchy
(def tree (analyze/build-hierarchy data))

;; Render to HTML string (no browser)
(def html (clj-compiler-view/render-html tree :size :loc :color :max-depth-raw))
```

## Available Metrics

| Metric | Description |
|--------|-------------|
| `:loc` | Lines of code |
| `:expressions-raw` | Form count from source (before macro expansion) |
| `:expressions-expanded` | AST node count (after macro expansion) |
| `:max-depth-raw` | Nesting depth in source |
| `:max-depth-expanded` | Nesting depth in AST |

**Raw vs Expanded**: A threading macro like `(-> x a b c)` has low raw depth (flat pipeline) but high expanded depth (nested function calls). Comparing these metrics reveals where macros hide complexity.

## Visualization Controls

- **Click** - Drill down one level into a namespace
- **Shift+Click** - Jump directly to the deepest namespace containing clicked item
- **Alt+Click** - Copy `namespace/function-name` to clipboard
- **Escape** - Zoom out one level (or clear search)
- **Search** - Type to find functions/namespaces by name

The info panel shows metrics on hover. Namespace cells show function counts; function cells show all metrics.

### Visual Indicators

- **Cell size** - Controlled by selected size metric (default: expression count)
- **Cell color** - Blue (low) to red (high) based on selected color metric
- **Striped pattern** - Unused code (defined but never referenced)
- **Faded appearance** - Analysis failed (partial metrics from reader fallback)

## How It Works

1. **Parse** - Uses `tools.analyzer.jvm` to parse each namespace into an AST. This gives us the fully macro-expanded form of every definition.

2. **Extract Metrics** - Walks each `:def` node to compute metrics. Raw metrics come from `:raw-forms` (pre-expansion source), expanded metrics from the AST structure.

3. **Detect Unused Code** - Compares the set of defined vars against referenced vars across all analyzed namespaces. Anything defined but never referenced is marked unused.

4. **Build Hierarchy** - Converts flat `[{:ns "a.b" :name "fn" :metrics {...}}]` into nested `{:name "a" :children [{:name "b" :children [...]}]}` for D3.

5. **Render** - Injects the data as JSON into an HTML template with embedded D3.js. Opens in default browser.

### Limitations

Some namespaces break `tools.analyzer.jvm` (including the analyzer itself, `clojure.spec.*`, `clojure.reflect`). These fall back to reader-based analysis with partial metrics (no expanded metrics, no unused detection).

Check `@clj-compiler-view.analyze/errors` for namespaces that failed analysis.

## Troubleshooting

**"No implementation of method: :do-reflect" errors**

Protocol cache invalidation issue. Run:

```clojure
(clj-compiler-view.analyze/reset-analyzer!)
```

**Empty or missing metrics**

Check `@clj-compiler-view.analyze/errors` for analysis failures. Some namespaces cannot be analyzed; they fall back to reader-based metrics marked with `:failed? true`.

## Dependencies

- [tools.analyzer.jvm](https://github.com/clojure/tools.analyzer.jvm) - Clojure AST analysis
- [jsonista](https://github.com/metosin/jsonista) - JSON serialization
- [D3.js](https://d3js.org/) - clj-compiler-view visualization (loaded from CDN)
