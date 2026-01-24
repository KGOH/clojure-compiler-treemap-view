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
