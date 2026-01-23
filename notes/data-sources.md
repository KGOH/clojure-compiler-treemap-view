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

---

## 2. Class Loader Instrumentation (Not yet implemented)

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

## Implementation Priority

1. **Compiler hook** (done) - your code's quality metrics
2. **Class loader** (next) - runtime footprint, bytecode size

---

## Questions to Resolve

1. For class loader: filter JDK classes? (java.*, javax.*, sun.*) → Yes, as option
2. How to correlate Clojure class names (`ns$fn`) back to source? → Naming convention parse
3. Memory overhead of class loader instrumentation?
