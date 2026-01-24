# TODO

## High Priority

### 1. Add agent-loaded runtime check
**File:** `agent.clj`

No runtime check if the metrics agent is loaded. Users get cryptic `ClassNotFoundException` instead of a helpful message.

**Solution:** Add a check at namespace load time:
```clojure
(defn- check-agent-loaded! []
  (try
    (Class/forName "clojure.metrics.MetricsBridge")
    (catch ClassNotFoundException _
      (throw (ex-info "Metrics agent not loaded. Start REPL with: clj -M:agent" {})))))
```

### 2. Add SRI hash to D3 CDN or vendor locally
**File:** `treemap.html:7`

D3 loaded from CDN without SRI (Subresource Integrity) hash. If CDN is compromised, visualization breaks or worse.

**Options:**
- Add integrity attribute: `<script src="https://d3js.org/d3.v7.min.js" integrity="sha384-..." crossorigin="anonymous"></script>`
- Vendor D3 locally in resources/

### 3. Add debounce to resize handler
**File:** `treemap.js:511`

No debouncing on `window.addEventListener('resize', render)`. Rapid resizing causes rapid re-renders.

**Solution:**
```javascript
let resizeTimeout;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimeout);
  resizeTimeout = setTimeout(render, 100);
});
```

### 4. Thread safety: document single-threaded requirement
**Files:** `analyze.clj`, `agent.clj`

The `with-capture` macro clears/drains global buffers (`MetricsBridge.BUFFER`, `VarRefBridge.REFERENCES`). Concurrent calls corrupt each other's data. This is a REPL tool so concurrent analysis is unlikely, but should be documented.

**Solution:** Add docstring warning to `analyze-ns`, `analyze-nses`, and `with-capture` that analysis is not thread-safe.

### 5. Unbounded buffer growth
**Files:** `MetricsBridge.java`, `VarRefBridge.java`

Capture buffers grow indefinitely during REPL usage. If user never drains (never calls analyze functions), memory grows.

**Options:**
- Add max capacity with eviction (complex)
- Add size warning when buffer exceeds threshold (simple)
- Document that `clear!` should be called periodically in long sessions
