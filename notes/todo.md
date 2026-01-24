# TODO

## High Priority

### 1. Vendor D3.js locally
**Files:** `treemap.html`, `core.clj`

D3 loaded from CDN. Problems:
- Requires internet (fails in air-gapped environments, travel)
- No SRI hash (security concern if CDN compromised)
- Network latency on every visualization

**Recommendation:** Vendor D3 locally. This is a REPL tool, not a web app. Offline-first is the right model.

**Implementation:**
1. Download D3 v7 minified (~280KB) to `resources/d3.v7.min.js`
2. Update `open-html` to write D3 alongside the temp HTML file:
```clojure
(defn open-html [html]
  (let [html-file (java.io.File/createTempFile "treemap-" ".html")
        d3-file (java.io.File. (.getParent html-file) "d3.v7.min.js")]
    (.deleteOnExit html-file)
    (.deleteOnExit d3-file)
    (spit d3-file (slurp-resource "d3.v7.min.js"))
    (spit html-file html)
    (browse/browse-url (str "file://" (.getAbsolutePath html-file)))
    (.getAbsolutePath html-file)))
```
3. Change `treemap.html` to reference local file: `<script src="d3.v7.min.js"></script>`

**Why not SRI on CDN:** SRI prevents tampering but still requires network. Doesn't solve offline use.

**Why not inline:** 280KB in template string makes HTML unreadable. No practical benefit.

### 2. Thread safety: document single-threaded requirement
**Files:** `analyze.clj`, `agent.clj`

The `with-capture` macro clears/drains global buffers (`MetricsBridge.BUFFER`, `VarRefBridge.REFERENCES`). Concurrent calls corrupt each other's data. This is a REPL tool so concurrent analysis is unlikely, but should be documented.

**Solution:** Add docstring warning to `analyze-ns`, `analyze-nses`, and `with-capture` that analysis is not thread-safe.

### 3. Unbounded buffer growth
**Files:** `MetricsBridge.java`, `VarRefBridge.java`

Capture buffers grow indefinitely during REPL usage. If user never drains (never calls analyze functions), memory grows.

**Options:**
- Add max capacity with eviction (complex)
- Add size warning when buffer exceeds threshold (simple)
- Document that `clear!` should be called periodically in long sessions
