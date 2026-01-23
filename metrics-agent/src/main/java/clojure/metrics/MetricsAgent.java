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
 * Java agent entry point for Clojure compiler instrumentation.
 *
 * This agent hooks into clojure.lang.Compiler.analyzeSeq to capture
 * def/defn forms as they are compiled.
 *
 * Usage: java -javaagent:metrics-agent.jar ...
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

        if (VERBOSE) System.out.println("[MetricsAgent] Installing Clojure compiler instrumentation...");

        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener() {
                @Override
                public void onDiscovery(String typeName, ClassLoader classLoader,
                                        JavaModule module, boolean loaded) {
                    // Only log Clojure compiler discovery in verbose mode
                    if (VERBOSE && typeName.equals("clojure.lang.Compiler")) {
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
            })
            .type(ElementMatchers.named("clojure.lang.Compiler"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(CompilerAdvice.class)
                    .on(ElementMatchers.named("analyzeSeq")
                        .and(ElementMatchers.isPrivate())
                        .and(ElementMatchers.isStatic()))))
            .installOn(inst);

        if (VERBOSE) System.out.println("[MetricsAgent] Installation complete");
    }

    /**
     * Alternative entry point for attaching to running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
