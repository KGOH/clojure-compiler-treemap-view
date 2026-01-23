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

From bytecode via ASM:

| Metric | Source | Value |
|--------|--------|-------|
| Bytecode size | `bytecode.length` | Raw instruction weight |
| Method count | ASM `visitMethod` | Complexity indicator |
| Field count | ASM `visitField` | Data complexity |
| Dependencies | ASM `visitMethodInsn`, `visitFieldInsn` | What this class references |
| Annotations | ASM `visitAnnotation` | Framework magic indicator |

```java
private static ClassMetrics parseWithASM(byte[] bytecode) {
    ClassReader reader = new ClassReader(bytecode);
    MetricsVisitor visitor = new MetricsVisitor();
    reader.accept(visitor, ClassReader.SKIP_DEBUG);
    return visitor.getMetrics();
}

class MetricsVisitor extends ClassVisitor {
    int methodCount = 0;
    int fieldCount = 0;
    Set<String> dependencies = new HashSet<>();

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                      String signature, String[] exceptions) {
        methodCount++;
        return new MethodVisitor(ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner,
                                        String name, String desc, boolean itf) {
                dependencies.add(owner);  // Track what this method calls
            }
        };
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc,
                                   String signature, Object value) {
        fieldCount++;
        return null;
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
 :metrics {:loc 5
           :expressions-raw 12
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
