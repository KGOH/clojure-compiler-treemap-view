# Data Sources for Code Metrics

Three complementary approaches to capture code metrics, each with different visibility.

## Trust Model: Source vs Bytecode

**Critical insight:** Source-based and bytecode-based analysis have fundamentally different trust levels.

| Analysis Type | What it shows | Trust level |
|---------------|---------------|-------------|
| Source-based | What code *claims* to be | Trust the publisher |
| Bytecode-based | What code *actually* executes | Ground truth |

**The "coincidence" everyone trusts:** AOT-compiled libraries bundle both `.class` (bytecode) and `.clj` (source) files. All source-based tools assume these match:

- `tools.analyzer.jvm` - reads .clj, assumes it matches .class
- `clojure.repl/source` - reads .clj, assumes it's what's running
- IDEs (Cursive, Calva) - show .clj, assume it's truth
- Var metadata (`:file`, `:line`) - points to .clj files

**There is no guarantee** that bundled source matches compiled bytecode. A library could:
- Compile from version A, bundle source from version B
- Inject extra code during compilation
- Ship handwritten bytecode with fabricated "source"

For 99.9% of legitimate libraries this is fine. But for security-sensitive analysis, only bytecode is truth.

---

## Two Views: Code Quality vs Runtime Footprint

These answer different questions and should be separate views in the UI.

### View 1: Code Quality (Source-based)

**Question:** "How complex is this code?"

**Data sources:** Compiler Hook, Runtime Vars, tools.analyzer

**Metrics:**
- LOC (lines of code)
- Expression count (raw and macro-expanded)
- Nesting depth
- Cyclomatic complexity (future)
- Unused vars

**Trust:** Relies on source files. Shows what code *claims* to be.

### View 2: Runtime Footprint (Bytecode-based)

**Question:** "What am I actually shipping to production?"

**Data source:** Class Loader instrumentation

**Metrics:**
- Bytecode size (bytes)
- Class count
- Load order/timing
- Package breakdown

**Trust:** Ground truth. Shows what *actually* executes.

---

## UI Design: Data Source Picker

Each view has a picker to select data source. Each source has its own applicable metrics.

```
┌─────────────────────────────────────────────────────────────┐
│  View: [Code Quality ▼]    Source: [Compiled This Session ▼]│
├─────────────────────────────────────────────────────────────┤
│  Size by: [LOC ▼]  Color by: [Max Depth ▼]                  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                    TREEMAP                           │   │
│  │                                                      │   │
```

### Code Quality View - Source Options

| Source | Coverage | Speed | Use when |
|--------|----------|-------|----------|
| Compiled This Session | Your code only | Instant | Developing |
| All Loaded Clojure | Everything with source | Fast | Reviewing deps |
| Specific Namespace | One ns deep-dive | Fast | Focused analysis |

**Metrics available:** LOC, expressions, depth, unused

### Runtime Footprint View - Source Options

| Source | Coverage | Speed | Use when |
|--------|----------|-------|----------|
| All Classes | Full JVM | Medium | Uberjar audit |
| Clojure Only | Clojure fns | Fast | Clojure bloat |
| Java Only | Java deps | Medium | AWS SDK problem |
| Exclude JDK | No java.*, sun.* | Medium | App deps only |

**Metrics available:** Bytecode size, class count

---

## 1. Compiler Hook (Implemented)

**What it captures:** Forms as they are compiled from source in the current session.

**Method:** ByteBuddy @Advice on `Compiler.analyzeSeq`

**Data available:**
- def name, namespace, line number
- Captures: def, defn, defn-, defmacro, defmulti
- Only sees source-compiled code

**Visibility:**
| Code Type | Visible? |
|-----------|----------|
| Your application code | Yes |
| Dependencies (source) | Yes |
| AOT-compiled libs | No |
| clojure.core | No |

**Use case:** "What code am I writing/compiling?"

**View:** Code Quality

---

## 2. Runtime Var Enumeration (Not yet implemented)

**What it captures:** All vars currently loaded in the JVM, using metadata.

**Method:** Iterate `(all-ns)` → `(ns-interns ns)` → `(meta var)`

**Data available:**
- def name, namespace, line number, file path
- arglists, docstrings
- Works for AOT code (metadata preserved in .clj files bundled with JARs)

**Visibility:**
| Code Type | Visible? |
|-----------|----------|
| Your application code | Yes |
| Dependencies (source) | Yes |
| AOT-compiled libs | Yes |
| clojure.core | Yes |

**Use case:** "What Clojure code is in my runtime?"

**Limitation:** Only sees Clojure vars, not Java classes. Trusts bundled source.

**View:** Code Quality

---

## 3. Class Loader Instrumentation (Not yet implemented)

**What it captures:** All JVM classes as they are loaded.

**Method:** ByteBuddy instrumentation on ClassLoader.loadClass or defineClass

**Data available:**
- Class name (e.g., `clojure.core$map`, `com.amazonaws.services.s3.AmazonS3Client`)
- Bytecode size
- Load timing
- Can distinguish Clojure fns (naming convention: `namespace$fn_name`)

**Visibility:**
| Code Type | Visible? |
|-----------|----------|
| Your application code | Yes |
| Dependencies (source) | Yes |
| AOT-compiled libs | Yes |
| clojure.core | Yes |
| Java libraries | Yes |
| JDK classes | Yes (optionally) |

**Use case:** "What am I shipping to production? Total runtime footprint."

**Note:** This is where AWS SDK bloat would be visible - hundreds of Java classes.

**View:** Runtime Footprint

---

## Comparison Matrix

| Aspect | Compiler Hook | Runtime Vars | Class Loader |
|--------|--------------|--------------|--------------|
| Clojure source line | Yes | Yes | No |
| Clojure AOT code | No | Yes | Yes |
| Java libraries | No | No | Yes |
| Bytecode size | No | No | Yes |
| Compile-time only | Yes | No | No |
| Real-time capture | Yes | No | Yes |
| Trust level | Source | Source | Bytecode |
| View | Code Quality | Code Quality | Runtime Footprint |

---

## Implementation Priority

1. **Compiler hook** (done) - your code's quality metrics
2. **Var enumeration** (next) - cheap, no agent, extends to clojure.core
3. **Class loader** (later) - only if "why is my uberjar 200MB" matters

---

## Questions to Resolve

1. ~~How to present three data sources in one treemap UI?~~ → Two views with picker
2. For class loader: filter JDK classes? (java.*, javax.*, sun.*) → Yes, as option
3. How to correlate Clojure class names (`ns$fn`) back to source? → Naming convention parse
4. Memory overhead of class loader instrumentation?
5. Should bytecode size be available in Code Quality view for correlation?
