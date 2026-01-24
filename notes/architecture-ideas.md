# Architecture Ideas

## Idea 1: Separate Data Generation from Visualization

### Current Problem

The tool generates HTML with data embedded. This couples data and viewer tightly:
- Can't share data without the giant HTML file
- Can't compare datasets in one viewer
- Can't use data in CI pipelines without parsing HTML

### Proposed Separation

```
Current:  analyze → build-hierarchy → render-html (embed data) → browser

Proposed:
  Data:   analyze → build-hierarchy → write JSON/EDN
  Viewer: Static HTML/JS loads data from file upload, URL, or embedded
```

### Benefits

- **Reusable viewer**: One viewer, many datasets
- **Shareable**: Send JSON + viewer URL instead of giant HTML
- **CI-friendly**: Output `metrics.json` as build artifact
- **Debuggable**: Inspect raw data without browser dev tools

### Data Loading Options

| Method | Pros | Cons |
|--------|------|------|
| File upload | Works offline, no server | Manual step each time |
| URL param | Shareable links | Requires hosting with CORS |
| Embedded | Quick REPL use | Current approach, keep as fallback |

### Suggested API

```clojure
;; Quick mode (unchanged)
(treemap! '[my.ns])  ; → opens browser with embedded data

;; Export mode (new)
(export-metrics '[my.ns] "metrics.json")
```

### Data Format

Flat, tool-agnostic format (viewer builds D3 hierarchy):

```clojure
{:version 1
 :generated "2026-01-24T..."
 :namespaces ["my.ns"]
 :definitions [{:name "my-fn"
                :ns "my.ns"
                :metrics {...}
                :unused? false}]}
```

---

## Idea 2: Build-Time Analysis

### Current Problem

Analysis only works in REPL with agent loaded. Can't generate metrics during CI/build.

### Proposed Solution

Add a build alias that runs analysis and outputs JSON:

```clojure
;; deps.edn
{:aliases
 {:metrics {:jvm-opts ["-javaagent:metrics-agent/target/metrics-agent.jar"]
            :exec-fn clojure-compiler-treemap-view.build/generate
            :exec-args {:output "target/metrics.json"}}}}
```

Run: `clj -X:metrics`

### Output Location

Separate artifact (not bundled in JAR):
```
target/
├── my-project.jar
└── metrics.json
```

### CI Integration

```yaml
# .github/workflows/metrics.yml
- name: Generate metrics
  run: clj -X:metrics
- name: Upload artifact
  uses: actions/upload-artifact@v4
  with:
    name: code-metrics
    path: target/metrics.json
```

### Use Cases

- Code review: PR includes metrics diff
- Tech debt tracking: Plot metrics over time
- Documentation: Published library includes metrics

### Constraints

- Agent still required at analysis time (fundamental)
- Namespaces must be loadable (all deps available)
- Adds build time overhead

---

## Implementation Phases

**Phase 1: Data export** (low effort)
- Add `export-metrics` function
- Output JSON with version field
- Keep `treemap!` unchanged

**Phase 2: Standalone viewer** (medium effort)
- Refactor `treemap.js` for external data loading
- Add file upload and `?data=URL` support
- Host on GitHub Pages

**Phase 3: Build integration** (medium effort)
- Add `:metrics` alias
- Document CI workflow

**Phase 4: Diff and tracking** (future)
- CLI to compare two metrics files
- Trend visualization
