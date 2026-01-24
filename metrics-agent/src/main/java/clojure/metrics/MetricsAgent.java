package clojure.metrics;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Java agent entry point for Clojure metrics instrumentation.
 *
 * Supports two instrumentation modes:
 * - Compiler Hook: captures def/defn forms as they compile (source-based)
 * - Class Loader: captures all classes as they load (bytecode-based)
 *
 * Usage:
 *   java -javaagent:metrics-agent.jar              # both enabled (default)
 *   java -javaagent:metrics-agent.jar=compiler     # only compiler hook
 *   java -javaagent:metrics-agent.jar=classloader  # only class loader
 *   java -javaagent:metrics-agent.jar=compiler,classloader  # explicit both
 */
public class MetricsAgent {

    private static volatile boolean installed = false;

    private static final boolean VERBOSE = Boolean.getBoolean("metrics.agent.verbose");

    public static void premain(String agentArgs, Instrumentation inst) {
        if (installed) {
            if (VERBOSE) System.err.println("[MetricsAgent] Already installed, skipping");
            return;
        }
        installed = true;

        // Parse feature flags
        boolean enableCompiler = true;
        boolean enableClassLoader = true;

        if (agentArgs != null && !agentArgs.isEmpty()) {
            // If args specified, only enable what's explicitly listed
            enableCompiler = agentArgs.contains("compiler");
            enableClassLoader = agentArgs.contains("classloader");
        }

        if (VERBOSE) {
            System.out.println("[MetricsAgent] Installing instrumentation...");
            System.out.println("[MetricsAgent]   Compiler hook: " + enableCompiler);
            System.out.println("[MetricsAgent]   Class loader: " + enableClassLoader);
        }

        // Install Class Loader transformer
        if (enableClassLoader) {
            inst.addTransformer(new ClassLoaderTransformer(), false);
            if (VERBOSE) System.out.println("[MetricsAgent] Class loader transformer installed");
        }

        // Install Compiler hook via ByteBuddy
        if (enableCompiler) {
            installCompilerHook(inst);
        }

        if (VERBOSE) System.out.println("[MetricsAgent] Installation complete");
    }

    /**
     * Install ByteBuddy advice on Compiler.analyzeSeq
     */
    private static void installCompilerHook(Instrumentation inst) {
        AgentBuilder.Listener listener = new AgentBuilder.Listener() {
            @Override
            public void onDiscovery(String typeName, ClassLoader classLoader,
                                    JavaModule module, boolean loaded) {
                if (VERBOSE && (typeName.equals("clojure.lang.Compiler") ||
                                typeName.startsWith("clojure.lang.Compiler$"))) {
                    System.out.println("[MetricsAgent] Discovered: " + typeName + " (loaded=" + loaded + ")");
                }
            }

            @Override
            public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                        JavaModule module, boolean loaded, DynamicType dynamicType) {
                if (VERBOSE) System.out.println("[MetricsAgent] Transformed: " + typeDescription.getName());
            }

            @Override
            public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                                 JavaModule module, boolean loaded) {
                // Ignore
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                               boolean loaded, Throwable throwable) {
                System.err.println("[MetricsAgent] Error transforming: " + typeName);
                throwable.printStackTrace();
            }

            @Override
            public void onComplete(String typeName, ClassLoader classLoader, JavaModule module,
                                  boolean loaded) {
                // Ignore
            }
        };

        // Hook 1: Compiler.macroexpand for def form capture
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(listener)
            .type(ElementMatchers.named("clojure.lang.Compiler"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder
                    // Unified hook on macroexpand: captures both raw (enter) and expanded (exit)
                    .visit(Advice.to(MacroexpandUnifiedAdvice.class)
                        .on(ElementMatchers.named("macroexpand")
                            .and(ElementMatchers.isStatic())
                            .and(ElementMatchers.takesArguments(1)))))
            .installOn(inst);

        if (VERBOSE) System.out.println("[MetricsAgent] Macroexpand hook installed");

        // Hook 2: VarExpr constructor for var reference capture
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(listener)
            .type(ElementMatchers.named("clojure.lang.Compiler$VarExpr"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(VarRefAdvice.class)
                    .on(ElementMatchers.isConstructor())))
            .installOn(inst);

        if (VERBOSE) System.out.println("[MetricsAgent] VarExpr hook installed");

        // Hook 3: TheVarExpr constructor for #'var references
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(listener)
            .type(ElementMatchers.named("clojure.lang.Compiler$TheVarExpr"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(VarRefAdvice.class)
                    .on(ElementMatchers.isConstructor())))
            .installOn(inst);

        if (VERBOSE) System.out.println("[MetricsAgent] TheVarExpr hook installed");
    }

    /**
     * Alternative entry point for attaching to running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
