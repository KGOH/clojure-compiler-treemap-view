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

;; Analyze namespaces and write metrics
(def analysis (cctv/analyze-nses '[my.app.core my.app.handlers]))
(cctv/write-metrics (:result analysis) "metrics.prom")

;; Open viewer in browser (viewer.html loads the .prom file)
(clojure.java.browse/browse-url
  (str "file://" (.getAbsolutePath (java.io.File. "viewer.html"))
       "?data=file://" (.getAbsolutePath (java.io.File. "metrics.prom"))))
```

### Discovering What You Actually Loaded

`analyze-nses` requires you to name namespaces upfront. But when you `require` code, the agent captures everything that compiles, including transitive dependencies you might not realize you're pulling in.

Use `analyze-captured` to see what's actually been compiled during your session:

```clojure
;; Use your REPL normally
(require '[some.library])
(some.library/do-stuff)

;; Then see everything that compiled
(def analysis (cctv/analyze-captured))
(cctv/write-metrics (:result analysis) "metrics.prom")
```

This reveals the true cost of dependencies. A "simple" library that pulls in 50 helper namespaces? You'll see it in the metrics.

### Buffer Drainage

All analysis functions (`analyze-nses`, `analyze-captured`) drain the capture buffer. After calling any of these, the buffer is empty until more code compiles. This means subsequent calls return empty results unless you've loaded more code. Store the result if you need it later. This design exists because there's only one Clojure compiler per JVM, and the global buffer prevents stale data from previous analyses.

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
4. **Export** - Writes metrics in Prometheus format (zero dependencies)
5. **Visualize** - Static `viewer.html` loads the metrics file and renders with D3.js

## Dependencies

**Runtime**: Only `org.clojure/clojure` - zero third-party dependencies.

**Viewer**: D3.js is inlined in `viewer.html` for browser visualization. This is a static file, not a runtime dependency of the Clojure library.

**Build-time**: Java metrics agent (in `metrics-agent/` directory) must be loaded via `-javaagent` flag.
