# Java Metrics: Runtime-Attached Approach

## The Problem

Current treemap only shows Clojure code. A thin wrapper hides massive dependencies:

```clojure
(defn upload [bucket key data]
  (s3/put-object bucket key data))  ; Shows as ~5 expressions
```

Reality: This pulls in 2.3MB of AWS SDK (hundreds of classes). The "true cost" is invisible.

## Why Not Static Analysis?

Static JAR analysis (scanning classpath) is a "shadow" approach - same problem as tools.analyzer for Clojure. We're reading files, not observing the actual program.

We want to be **attached to the actual runtime**.

## The Parallel: Clojure vs Java

| | Clojure | Java |
|-|---------|------|
| **Compilation** | Runtime (in JVM) | Build time (javac) |
| **Moment of truth** | `Compiler.analyzeSeq()` | `ClassLoader.defineClass()` |
| **Agent hook** | Instrument `Compiler` class | `ClassFileTransformer` API |
| **What we capture** | Forms as they compile | Classes as they load |

Java's "compilation" already happened at build time. The runtime equivalent is **class loading** - when bytecode becomes a live Class object.

```
Clojure: source → Compiler.analyzeSeq() → bytecode → execution
                        ↑
                   HOOK HERE

Java:    source → javac → bytecode → ClassLoader → Class object → execution
                                          ↑
                                     HOOK HERE
```

## One Agent, Two Hooks

A single Java agent captures both Clojure compilation and Java class loading:

```java
public class MetricsAgent {
    public static void premain(String args, Instrumentation inst) {
        // Hook 1: Clojure compilation (see clojure_reader_proximity.md)
        // Intercepts Compiler.analyzeSeq() to see forms before/after macroexpansion
        inst.addTransformer(new ClojureCompilerTransformer());

        // Hook 2: Java class loading (ALL classes, including Clojure-generated)
        inst.addTransformer((loader, className, cls, domain, bytecode) -> {
            if (shouldTrack(className)) {
                // bytecode IS the real class bytes
                ClassMetrics metrics = parseWithASM(bytecode);
                recordClassLoad(className, metrics);
            }
            return null;  // observe only, don't modify
        });
    }

    private static boolean shouldTrack(String className) {
        // Skip JDK internals, focus on application + deps
        return className != null
            && !className.startsWith("java/")
            && !className.startsWith("jdk/")
            && !className.startsWith("sun/");
    }
}
```

## What ClassFileTransformer Captures

This is truly "attached to the actual program":

| Aspect | What We See |
|--------|-------------|
| **What actually loads** | Not classpath contents, but what the program uses |
| **Runtime-generated classes** | Spring proxies, Hibernate entities, cglib mocks |
| **Real bytecode** | After build-time weaving (AspectJ, Lombok, etc.) |
| **Load order** | Which classes loaded first, dependency chains |
| **Clojure-generated classes** | `fn__12345`, `reify__67890`, etc. |

## Metrics We Can Extract

### Without ASM

| Metric | Source | Description |
|--------|--------|-------------|
| Bytecode size | `bytecode.length` | Raw class file size in bytes |

### With ASM: Structural Metrics (from ClassVisitor)

| Metric | ASM Callback | Description |
|--------|--------------|-------------|
| Field count | `visitField()` | Number of fields (closed-overs for Clojure fns) |
| Method count | `visitMethod()` | Number of methods |
| Interface count | `visit()` interfaces param | Number of implemented interfaces |
| Annotation count | `visitAnnotation()` | Number of class-level annotations |

### With ASM: Instruction Metrics (from MethodVisitor)

| Metric | ASM Callback | Description |
|--------|--------------|-------------|
| Instruction count | All `visit*Insn()` methods | Total opcodes across all methods |
| Branch count | `visitJumpInsn()` | IFEQ, IFNE, GOTO, etc. - control flow complexity |
| Invoke count | `visitMethodInsn()` | Method calls (invokevirtual, invokestatic, etc.) |
| Field access count | `visitFieldInsn()` | GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC |
| Try-catch count | `visitTryCatchBlock()` | Exception handler count |
| Local variable count | `visitLocalVariable()` | Only if debug info present |

### With ASM: Dependency Metrics

| Metric | ASM Callback | Description |
|--------|--------------|-------------|
| Outgoing class refs | `visitMethodInsn()` owner param | Distinct classes this class calls |
| Type refs | `visitTypeInsn()` | NEW, CHECKCAST, INSTANCEOF targets |

### ASM Options

**Option 1: Add explicit dependency**
```xml
<dependency>
    <groupId>org.ow2.asm</groupId>
    <artifactId>asm</artifactId>
</dependency>
```
Classes: `org.objectweb.asm.ClassReader`, `org.objectweb.asm.ClassVisitor`, etc.

**Option 2: Use ByteBuddy's shaded ASM** (no new dependency)

ByteBuddy bundles a complete copy of ASM with renamed packages:
- Original: `org.objectweb.asm.ClassReader`
- Shaded: `net.bytebuddy.jar.asm.ClassReader`

Same API, already in classpath since we use ByteBuddy for compiler hooks.

---

## Metric Evaluation for Clojure Treemap

Not all bytecode metrics are equally useful for visualizing Clojure code. Here's an assessment:

### High Value: Field Count (Closed-Overs)

For Clojure-generated `namespace$fn__12345` classes, **fields = closed-over values**.

```clojure
(let [db-conn (get-connection)
      config (load-config)
      cache (atom {})]
  (fn [request]
    (process request db-conn config cache)))
```

The generated fn class has fields for `db-conn`, `config`, and `cache`.

**What high field count reveals:**
- Memory footprint per function instance (matters if you create many)
- Implicit coupling - functions with many closed-overs are tangled with their creation context
- Potential for accidental head retention (closing over large collections)
- Refactoring candidates (could be split or use explicit state)

**Treemap use:** Color by field count to spot "heavy" closures instantly.

### Medium-High Value: Instruction Count

Better than raw bytecode size. Byte size includes constant pool entries, debug info, method signatures, etc. Instruction count is closer to "how much work does this code actually do."

Requires walking each method's Code attribute and counting opcodes.

### Medium Value: Outgoing Reference Count

Simple scalar: how many distinct classes does this class call? (Not full dependency graph - just the count.)

Useful for identifying code with many external dependencies without building a full graph visualization.

### Medium Value: Branch Count

Number of conditional jump instructions (`IFEQ`, `IFNE`, `TABLESWITCH`, `LOOKUPSWITCH`, `GOTO`, etc.).

Proxy for **cyclomatic complexity at bytecode level**.

| Clojure construct | Branch instructions generated |
|-------------------|------------------------------|
| `if`, `when`, `when-not` | 1+ branches |
| `cond` | Multiple branches (one per clause) |
| `case` | `TABLESWITCH` or `LOOKUPSWITCH` |
| `loop/recur` | `GOTO` for the loop back |
| `or`, `and` | Short-circuit branches |

**Caveat:** For Clojure, high branch counts often just mean pattern matching (`cond`, `case`) which isn't necessarily bad. Conflates loops with conditionals.

### Low Value: Method Count

For Clojure fns, it's almost always 1-2 (`invoke`, `invokeStatic`). Only shows variation for `defrecord`, `deftype`, or `gen-class`. Not useful for treemap visualization of typical Clojure code.

### Skip: Annotations

Clojure doesn't use Java annotations meaningfully. Irrelevant.

### Skip: Full Dependency Graph

Tracking what classes each class calls is easy to collect, but it's a **graph metric**, not a treemap metric. You'd visualize it as a force-directed graph or dependency matrix. Different scope entirely - don't conflate with treemap work.

---

## Implementation: Using ByteBuddy's Shaded ASM

Since we already use ByteBuddy for compiler hooks, we can use its bundled ASM without adding a new dependency.

```java
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
```

### Recommended Metrics to Implement

Based on value for Clojure treemap visualization:

| Priority | Metric | Why |
|----------|--------|-----|
| 1 | Field count | Closed-overs - high value for Clojure fns |
| 2 | Instruction count | Better than byte size for "code volume" |
| 3 | Branch count | Complexity proxy |
| 4 | Outgoing class ref count | Coupling indicator |

### Performance

- ASM uses streaming visitor pattern - microseconds per class
- Already intercepting class loads, marginal cost is negligible
- No allocations during traversal except final metrics storage

---

```java
import net.bytebuddy.jar.asm.*;
import static net.bytebuddy.jar.asm.Opcodes.*;

private static ClassMetrics parseWithASM(byte[] bytecode) {
    ClassReader reader = new ClassReader(bytecode);
    MetricsVisitor visitor = new MetricsVisitor();
    reader.accept(visitor, ClassReader.SKIP_DEBUG);
    return visitor.getMetrics();
}

class MetricsVisitor extends ClassVisitor {
    int fieldCount = 0;
    int instructionCount = 0;
    int branchCount = 0;
    Set<String> outgoingRefs = new HashSet<>();

    MetricsVisitor() {
        super(ASM9);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc,
                                   String signature, Object value) {
        fieldCount++;
        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                      String signature, String[] exceptions) {
        return new MethodVisitor(ASM9) {
            @Override
            public void visitInsn(int opcode) {
                instructionCount++;
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                instructionCount++;
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                instructionCount++;
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                instructionCount++;
                branchCount++;  // IFEQ, IFNE, GOTO, etc.
            }

            @Override
            public void visitMethodInsn(int opcode, String owner,
                                        String name, String desc, boolean itf) {
                instructionCount++;
                outgoingRefs.add(owner);
            }

            // ... other visit*Insn methods for complete instruction count
        };
    }
}
```

## Clojure-Java Correlation

### Option A: Load-Time Attribution

Track which Clojure namespace was being compiled when Java classes loaded:

```java
// In ClojureCompilerTransformer:
public static ThreadLocal<String> currentNamespace = new ThreadLocal<>();

// Before analyzing a ns form:
currentNamespace.set("my.app");

// In ClassFileTransformer:
String loadedDuring = ClojureCompilerTransformer.currentNamespace.get();
// Now we know my.app triggered loading of com.amazonaws.services.s3.AmazonS3Client
```

### Option B: Call Graph Analysis

Build graph of what calls what from bytecode analysis:

```
my.app/upload (Clojure fn)
  → my.app$upload.invoke() (generated class)
    → amazonica.aws.s3$put_object.invoke()
      → com.amazonaws.services.s3.AmazonS3Client.putObject()
        → [transitive deps...]
```

Sum bytecode of reachable classes = "Java weight" of that function.

## Data Model

```clojure
;; Clojure function with Java weight
{:name "upload"
 :ns "my.app"
 :metrics {:expressions-raw 12
           ;; Java metrics (from agent)
           :java-classes-loaded 47
           :java-weight-bytes 2340000
           :java-packages #{"com.amazonaws.services.s3"
                            "com.amazonaws.http"
                            "org.apache.http"}}}

;; Java class (sibling in unified treemap)
{:name "AmazonS3Client"
 :package "com.amazonaws.services.s3"
 :type :java-class
 :metrics {:bytecode-size 45678
           :method-count 127
           :field-count 23
           :dependencies 34}}
```

## Visualization Options

### Option A: Java Weight as Color Metric

Clojure treemap, colored by Java dependency weight:

```
Size: expressions-raw
Color: java-weight-bytes

gmonit.collector.core  -> 12KB (green - minimal deps)
my.aws.wrapper         -> 2.3MB (red - heavy deps)
```

### Option B: Unified Treemap

Java packages alongside Clojure namespaces:

```
root/
  gmonit/
    collector/ -> Clojure functions
  com/
    amazonaws/ -> Java classes
```

Challenge: Java packages may visually dwarf Clojure code.

### Option C: Drill-Through

Click Clojure ns → see its Java dependencies as sub-treemap.

## Implementation Architecture

```
metrics-agent/
├── src/main/java/
│   ├── MetricsAgent.java           # premain entry point
│   ├── ClojureCompilerTransformer.java  # Hook Compiler.analyzeSeq
│   ├── JavaClassTransformer.java   # ClassFileTransformer for all classes
│   ├── ASMMetricsVisitor.java      # Extract metrics from bytecode
│   └── MetricsBridge.java          # ThreadLocal + Var bridge to Clojure
├── src/main/resources/
│   └── META-INF/MANIFEST.MF        # Premain-Class: MetricsAgent
└── pom.xml                         # Dependencies: ASM, ByteBuddy
```

Usage:
```bash
java -javaagent:metrics-agent.jar -jar myapp.jar
```

Or in deps.edn:
```clojure
:jvm-opts ["-javaagent:metrics-agent.jar"]
```

## Practical Value

### See the True Cost
A 10-line Clojure wrapper showing 2.3MB Java weight reveals the actual footprint.

### Identify Heavy Dependencies
AWS SDK, Jackson, Apache HTTP, Spring - see their real impact.

### Track Runtime-Generated Code
Spring proxies, Hibernate entities, Clojure-generated fn classes - all visible.

### Understand Startup Cost
Class load order shows what loads during init vs on-demand.

### Measure "Clojure Efficiency"
```
ratio = clojure_bytecode / (clojure_bytecode + java_bytecode)
```
- Pure Clojure app: ~90%
- Thin AWS wrapper: ~2%

Neither is inherently bad, but it reveals what you're actually shipping.
