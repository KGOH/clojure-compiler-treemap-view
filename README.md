# clojure-compiler-treemap-view

Visualize Clojure codebase structure as an interactive treemap. See which functions are large, complex, or unused at a glance.

**[Live Demo](https://kgoh.github.io/clojure-compiler-treemap-view/)** - Treemap of this project's own codebase

Heavily inspired by [Jonathan Blow's code metrics viewer](https://youtu.be/IdpD5QIVOKQ?t=1192) for the JAI programming language.

## Why Use This

- **See your codebase structure** - Namespaces become nested rectangles, functions become cells sized by complexity
- **Find refactoring targets** - Large red cells are complex functions that may need splitting
- **Detect dead code** - Unused vars are highlighted with a striped pattern
- **Compare source vs expansion** - Threading macros look simple in source but expand into deep nesting; see both views

## Installation

```clojure
;; deps.edn
{:deps {clojure-compiler-treemap-view/clojure-compiler-treemap-view {:local/root "path/to/clojure-compiler-treemap-view"}}}
```

**Important**: This tool requires a Java agent to instrument the Clojure compiler. Add the `:agent` alias to your deps.edn or use the provided one.

## Usage

```bash
# Start REPL with the metrics agent
clj -M:agent
```

```clojure
(require '[clojure-compiler-treemap-view.core :as cctv])

;; Analyze namespaces and open visualization in browser
(cctv/treemap! '[my.app.core my.app.handlers])

;; Analyze all loaded namespaces
(cctv/treemap! (->> (all-ns) (map ns-name)))

;; Analyze all loaded namespaces matching a prefix
(cctv/treemap! (->> (all-ns)
                    (map ns-name)
                    (filter #(str/starts-with? (str %) "my.app."))))
```

### Programmatic Use

```clojure
(require '[clojure-compiler-treemap-view.core :as cctv])
(require '[clojure-compiler-treemap-view.analyze :as analyze])

;; Get raw analysis data
(def data (analyze/analyze-nses '[my.namespace]))

;; Build D3-compatible hierarchy
(def tree (analyze/build-hierarchy data))

;; Render to HTML string (no browser)
(def html (cctv/render-html tree :size :expressions-raw :color :max-depth-raw))
```

## Available Metrics

| Metric | Description |
|--------|-------------|
| `:expressions-raw` | Form count from source (before macro expansion) |
| `:expressions-expanded` | Form count (after macro expansion) |
| `:max-depth-raw` | Nesting depth in source |
| `:max-depth-expanded` | Nesting depth after macro expansion |

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

## How It Works

1. **Capture** - A Java agent hooks into the Clojure compiler to capture def forms both before and after macro expansion. This gives us the original source form and the fully expanded form.

2. **Extract Metrics** - Walks each captured form to compute metrics. Raw metrics come from pre-expansion forms, expanded metrics from post-expansion forms.

3. **Detect Unused Code** - The agent also captures var references during compilation. Compares defined vars against referenced vars to find unused code.

4. **Build Hierarchy** - Converts flat `[{:ns "a.b" :name "fn" :metrics {...}}]` into nested `{:name "a" :children [{:name "b" :children [...]}]}` for D3.

5. **Render** - Injects the data as JSON into an HTML template with embedded D3.js. Opens in default browser.

## Troubleshooting

**Agent not loaded**

Make sure you start the REPL with the agent:
```bash
clj -M:agent
```

Without the agent, all analysis functions will fail.

**Empty or missing metrics**

Check `@clojure-compiler-treemap-view.analyze/errors` for analysis failures. Namespaces that fail to load won't have any captured data.

## Dependencies

- [jsonista](https://github.com/metosin/jsonista) - JSON serialization
- [D3.js](https://d3js.org/) - Treemap visualization (loaded from CDN)
- Java metrics agent - Compiler instrumentation (included in `metrics-agent/` directory)
