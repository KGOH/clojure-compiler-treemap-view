# Data Sources for Code Metrics

Two data sources for capturing code metrics, each with different visibility and trust levels.

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

## UI Design: Source Picker

Single picker for data source. Available metrics depend on source type.

```
┌─────────────────────────────────────────────────────────────┐
│  Source: [Compiler Hook ▼]                                  │
│          ├── Compiler Hook      → LOC, depth, expressions   │
│          └── Class Loader       → bytecode size, class count│
├─────────────────────────────────────────────────────────────┤
│  Size by: [LOC ▼]  Color by: [Max Depth ▼]                  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                    TREEMAP                           │   │
│  │                                                      │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Source: Compiler Hook (Source-based)

**Question:** "How complex is the code I'm compiling?"

**Metrics available:** LOC, expressions (raw/expanded), nesting depth, unused vars

**Trust:** Source-based. Shows what code *claims* to be.

### Source: Class Loader (Bytecode-based)

**Question:** "What am I actually shipping to production?"

**Metrics available:** Bytecode size, class count

**Filter options:**
- All Classes (full JVM)
- Clojure Only (namespace$fn pattern)
- Java Only (exclude Clojure)
- Exclude JDK (no java.*, sun.*)

**Trust:** Ground truth. Shows what *actually* executes.

---

## 1. Compiler Hook (Implemented)

**What it captures:** Forms as they are compiled from source in the current session.

**Method:** Dual ByteBuddy @Advice hooks:
- `Compiler.macroexpand` → captures **raw** forms (pre-expansion)
- `Compiler.analyzeSeq` → captures **expanded** forms (post-expansion)

**Data captured:**
- `phase`: "raw" or "expanded"
- `op`: defn, defn-, defmacro, defmulti (raw) or def (expanded)
- `name`, `ns`, `line`, `end-line`
- `form`: actual Clojure data structure (not stringified)

**Raw vs Expanded example:**
```
Raw:      (defn foo [x] (-> x inc dec))
Expanded: (def foo (clojure.core/fn ([x] (-> x inc dec))))
```

Note: `defmulti` only appears in raw (expands to `let` block, not bare `def`).

**Design:** Forms stored as objects, metrics computed lazily in Clojure (keeps hook minimal).

**Visibility:**
| Code Type | Visible? |
|-----------|----------|
| Your application code | Yes |
| Dependencies (source) | Yes |
| AOT-compiled libs | No |
| clojure.core | No |

**Use case:** "What code am I writing/compiling?"

---

## 2. Class Loader Instrumentation (Implemented)

**What it captures:** All JVM classes as they are loaded.

**Method:** `Instrumentation.addTransformer` (simpler than ByteBuddy for this use case)

**Data captured:**
- Class name (e.g., `clojure.core$map`, `com.amazonaws.services.s3.AmazonS3Client`)
- Bytecode size (bytes)
- Filters out JDK classes (java.*, javax.*, sun.*, jdk.*)

**Clojure function detection:** Classes matching `namespace$fn_name` pattern are identified as Clojure functions. Underscores converted back to hyphens.

**Clojure API:**
```clojure
(agent/runtime-footprint)      ; summary: total classes, bytes, clojure vs java
(agent/get-loaded-classes)     ; map of class-name -> bytecode-size
(agent/classes-by-namespace)   ; grouped by Clojure namespace
(agent/largest-classes 10)     ; top N by bytecode size
```

**Visibility:**
| Code Type | Visible? |
|-----------|----------|
| Your application code | Yes |
| Dependencies (source) | Yes |
| AOT-compiled libs | Yes |
| clojure.core | Yes |
| Java libraries | Yes |
| JDK classes | No (filtered) |

**Use case:** "What am I shipping to production? Total runtime footprint."

**Note:** This is where AWS SDK bloat would be visible - hundreds of Java classes.

---

## Omitted: Runtime Var Enumeration

**Why omitted:** It's a fallback approach that doesn't capture what's actually compiled or loaded.

- Iterates `(all-ns)` → `(ns-interns ns)` → `(meta var)`
- Gets `:file` and `:line` from var metadata
- But this just points to bundled .clj files - an index, not actual compilation/loading data
- To get metrics, you'd still need to read and analyze those .clj files
- Same trust assumption as Compiler Hook, but indirect

**If needed later:** Could serve as a way to extend coverage to AOT Clojure (clojure.core) by re-analyzing bundled source files. But this is a "claimed source" approach, not a direct observation of what was compiled/loaded.

---

## Comparison Matrix

| Aspect | Compiler Hook | Class Loader |
|--------|--------------|--------------|
| Clojure source line | Yes | No |
| Clojure AOT code | No | Yes |
| Java libraries | No | Yes |
| Bytecode size | No | Yes |
| Code complexity metrics | Yes | No |
| Real-time capture | Yes | Yes |
| Trust level | Source | Bytecode |

---

## Implementation Status

1. **Compiler hook** ✓ - dual hooks (raw + expanded), forms as objects
2. **Class loader** ✓ - bytecode size, filtered JDK classes

**Agent modes:**
```bash
clj -J-javaagent:metrics-agent/target/metrics-agent-0.1.0-SNAPSHOT.jar           # both
clj -J-javaagent:metrics-agent/target/metrics-agent-0.1.0-SNAPSHOT.jar=compiler  # compiler only
clj -J-javaagent:metrics-agent/target/metrics-agent-0.1.0-SNAPSHOT.jar=classloader # loader only
```

---

## Next Steps

1. Compute metrics (expressions, depth) from captured forms in Clojure
2. Hook `registerVar` for unused var detection
3. Replace tools.analyzer.jvm with hook-based analysis (see analyzer-migration.md)
