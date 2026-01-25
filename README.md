# clojure-compiler-treemap-view

A Java agent that instruments the Clojure compiler to collect code metrics, then displays them as an interactive treemap. Use it to find bloat, complexity hotspots, and dead code in your codebase.

**[Live Demo](https://kgoh.github.io/clojure-compiler-treemap-view/)** - Treemap of this project's own codebase

Heavily inspired by [Jonathan Blow's code metrics viewer](https://youtu.be/IdpD5QIVOKQ?t=1192) for the JAI programming language.

## What It Shows

- **Cell size** - Expression count (how much code)
- **Cell color** - Blue (shallow) to red (deep nesting)
- **Striped pattern** - Unused code (defined but never referenced)
- **Raw vs expanded** - See metrics before and after macro expansion

Threading macros like `(-> x a b c)` look flat in source but expand to deeply nested calls. Comparing raw vs expanded metrics reveals where macros hide complexity.

## Setup

### Build the Agent

```bash
cd metrics-agent && mvn package && cd ..
```

This creates `metrics-agent/target/metrics-agent-0.1.0-SNAPSHOT.jar`.

### Option A: Add Alias to deps.edn

```clojure
;; In your deps.edn :aliases
:agent {:jvm-opts ["-javaagent:path/to/metrics-agent-0.1.0-SNAPSHOT.jar"]}
```

Then start REPL with:
```bash
clj -M:agent
```

### Option B: Pass Agent Flag Directly

```bash
clj -J-javaagent:path/to/metrics-agent-0.1.0-SNAPSHOT.jar
```

The agent must be loaded at JVM startup. Without it, all analysis functions will fail.

## Usage

```clojure
(require '[clojure-compiler-treemap-view.core :as cctv])

;; Analyze namespaces and open visualization in browser
(cctv/treemap! '[my.app.core my.app.handlers])

;; Analyze all loaded namespaces matching a prefix
(require '[clojure.string :as str])
(cctv/treemap! (->> (all-ns)
                    (map ns-name)
                    (filter #(str/starts-with? (str %) "my.app."))))
```

### Programmatic Use

```clojure
(require '[clojure-compiler-treemap-view.analyze :as cctv.analyze])

;; Get raw analysis data
(def analysis (cctv.analyze/analyze-nses '[my.namespace]))
;; => {:result [...] :errors [...]}

;; Build D3-compatible hierarchy
(def tree (cctv.analyze/build-hierarchy (:result analysis)))

;; Render to HTML string (no browser)
(def html (cctv/render-html tree :size :expressions-raw :color :max-depth-raw))
```

Analysis functions return `{:result [...] :errors [...]}`. Check `:errors` for namespaces that failed to load.

### Discovering What You Actually Loaded

`treemap!` and `analyze-nses` require you to name namespaces upfront. But when you `require` code, the agent captures everything that compiles—including transitive dependencies you might not realize you're pulling in.

Use `analyze-captured` to see what's actually been compiled during your session:

```clojure
;; Use your REPL normally
(require '[some.library])
(some.library/do-stuff)

;; Then see everything that compiled
(let [{:keys [result]} (cctv.analyze/analyze-captured)
      tree (cctv.analyze/build-hierarchy result)]
  (cctv/open-html (cctv/render-html tree)))
```

This reveals the true cost of dependencies. A "simple" library that pulls in 50 helper namespaces? You'll see it on the treemap.

## Available Metrics

| Metric | Description |
|--------|-------------|
| `:expressions-raw` | Form count from source (before macro expansion) |
| `:expressions-expanded` | Form count (after macro expansion) |
| `:max-depth-raw` | Nesting depth in source |
| `:max-depth-expanded` | Nesting depth after macro expansion |

## Visualization Controls

- **Click** - Drill down into a namespace
- **Shift+Click** - Jump to deepest namespace containing clicked item
- **Alt+Click** - Copy `namespace/function-name` to clipboard
- **Escape** - Zoom out (or clear search)
- **/ or s** - Focus search box
- **Search** - Find functions/namespaces by name

## How It Works

1. **Capture** - Java agent hooks into the Clojure compiler to capture def forms before and after macro expansion
2. **Track References** - Agent captures var references during compilation for unused detection
3. **Extract Metrics** - Walks each captured form to compute expression counts and nesting depth
4. **Build Hierarchy** - Converts flat function list to nested tree structure for D3
5. **Render** - Injects data as JSON into HTML template with vendored D3.js, opens in browser

## Dependencies

- [jsonista](https://github.com/metosin/jsonista) - JSON serialization
- [D3.js](https://d3js.org/) - Treemap visualization (vendored locally)
- Java metrics agent - Compiler instrumentation (in `metrics-agent/` directory)
