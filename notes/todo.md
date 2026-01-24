# TODO

## High Priority

### 1. Unbounded buffer growth
**Files:** `MetricsBridge.java`, `VarRefBridge.java`

Capture buffers grow indefinitely during REPL usage. If user never drains (never calls analyze functions), memory grows.

**Options:**
- Add max capacity with eviction (complex)
- Add size warning when buffer exceeds threshold (simple)
- Document that `clear!` should be called periodically in long sessions
