package clojure.metrics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between the ClassLoaderTransformer and Clojure code.
 *
 * Stores class names and their metrics in a thread-safe map.
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics ClassLoadBridge])
 *   (ClassLoadBridge/getLoadedClasses)  ; returns map of class-name -> int[]
 *   (ClassLoadBridge/totalBytecodeSize) ; returns total bytes
 */
public class ClassLoadBridge {

    // Metrics array indices
    public static final int IDX_BYTECODE_SIZE = 0;
    public static final int IDX_FIELD_COUNT = 1;
    public static final int IDX_INSTRUCTION_COUNT = 2;
    public static final int METRICS_LENGTH = 3;

    /**
     * Map of class name (dotted format) to metrics array.
     * Index 0 = bytecode size, Index 1 = field count, Index 2 = instruction count
     */
    private static final ConcurrentHashMap<String, int[]> LOADED_CLASSES =
        new ConcurrentHashMap<>();

    /**
     * Map of class name (dotted format) to set of referenced class names.
     * Tracks outgoing references (method calls, field access, type instructions, inheritance).
     */
    private static final ConcurrentHashMap<String, Set<String>> CLASS_REFERENCES =
        new ConcurrentHashMap<>();

    /**
     * Capture a loaded class with metrics. Called by ClassLoaderTransformer.
     *
     * @param className Internal class name format (com/foo/Bar)
     * @param bytecodeSize Size of class bytecode in bytes
     * @param fieldCount Number of fields in the class (-1 if unknown)
     * @param instructionCount Total JVM instructions in the class (-1 if unknown)
     * @param referencedClasses Set of class names referenced by this class (internal format)
     */
    public static void capture(String className, int bytecodeSize, int fieldCount, int instructionCount,
                               Set<String> referencedClasses) {
        // Convert internal name (com/foo/Bar) to dotted (com.foo.Bar)
        String normalizedName = className.replace('/', '.');
        int[] metrics = new int[METRICS_LENGTH];
        metrics[IDX_BYTECODE_SIZE] = bytecodeSize;
        metrics[IDX_FIELD_COUNT] = fieldCount;
        metrics[IDX_INSTRUCTION_COUNT] = instructionCount;
        LOADED_CLASSES.put(normalizedName, metrics);

        // Store outgoing references (excluding self)
        if (referencedClasses != null && !referencedClasses.isEmpty()) {
            Set<String> normalized = new HashSet<>();
            for (String ref : referencedClasses) {
                String normRef = ref.replace('/', '.');
                if (!normRef.equals(normalizedName)) {
                    normalized.add(normRef);
                }
            }
            if (!normalized.isEmpty()) {
                CLASS_REFERENCES.put(normalizedName, normalized);
            }
        }
    }

    /**
     * Get a copy of all loaded classes with metrics.
     *
     * @return Map of class name to metrics array [bytecodeSize, fieldCount, instructionCount]
     */
    public static Map<String, int[]> getLoadedClasses() {
        return new HashMap<>(LOADED_CLASSES);
    }

    /**
     * Get the number of captured classes.
     */
    public static int classCount() {
        return LOADED_CLASSES.size();
    }

    /**
     * Get total bytecode size of all captured classes.
     */
    public static long totalBytecodeSize() {
        return LOADED_CLASSES.values().stream()
            .mapToLong(m -> m[IDX_BYTECODE_SIZE])
            .sum();
    }

    /**
     * Clear all captured classes and references.
     */
    public static void clear() {
        LOADED_CLASSES.clear();
        CLASS_REFERENCES.clear();
    }

    /**
     * Get a copy of all class references.
     *
     * @return Map of class name to set of referenced class names (dotted format)
     */
    public static Map<String, Set<String>> getClassReferences() {
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : CLASS_REFERENCES.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Get the union of all referenced classes.
     *
     * @return Set of all class names that are referenced by at least one loaded class
     */
    public static Set<String> getAllReferencedClasses() {
        Set<String> allRefs = new HashSet<>();
        for (Set<String> refs : CLASS_REFERENCES.values()) {
            allRefs.addAll(refs);
        }
        return allRefs;
    }

    /**
     * Get bytecode size for a specific class.
     *
     * @param className Dotted class name (com.foo.Bar)
     * @return Bytecode size or -1 if not found
     */
    public static int getBytecodeSize(String className) {
        int[] metrics = LOADED_CLASSES.get(className);
        return metrics != null ? metrics[IDX_BYTECODE_SIZE] : -1;
    }

    /**
     * Get field count for a specific class.
     *
     * @param className Dotted class name (com.foo.Bar)
     * @return Field count or -1 if not found/unknown
     */
    public static int getFieldCount(String className) {
        int[] metrics = LOADED_CLASSES.get(className);
        return metrics != null ? metrics[IDX_FIELD_COUNT] : -1;
    }

    /**
     * Get instruction count for a specific class.
     *
     * @param className Dotted class name (com.foo.Bar)
     * @return Instruction count or -1 if not found/unknown
     */
    public static int getInstructionCount(String className) {
        int[] metrics = LOADED_CLASSES.get(className);
        return metrics != null ? metrics[IDX_INSTRUCTION_COUNT] : -1;
    }
}
