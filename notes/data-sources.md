# Data Sources for Code Metrics

Three complementary approaches to capture code metrics, each with different visibility.

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

**Limitation:** Only sees Clojure vars, not Java classes.

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

---

## Potential Combined Approach

For complete visibility:

1. **Compiler hook** - detailed metrics for code you're actively developing
2. **Runtime vars** - full Clojure picture including AOT deps
3. **Class loader** - total JVM footprint including Java deps

Treemap could have views/filters:
- "My code" (compiler-captured, current session)
- "All Clojure" (runtime vars)
- "Full runtime" (class loader, grouped by package)

---

## Questions to Resolve

1. For runtime vars: enumerate at what point? On-demand vs continuous?
2. For class loader: filter JDK classes? (java.*, javax.*, sun.*)
3. How to correlate Clojure class names (`ns$fn`) back to source?
4. Memory overhead of class loader instrumentation?
5. How to present three data sources in one treemap UI?
