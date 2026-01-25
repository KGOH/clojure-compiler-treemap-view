package clojure.metrics;

import java.util.HashMap;
import java.util.Map;
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
    public static final int METRICS_LENGTH = 2;

    /**
     * Map of class name (dotted format) to metrics array.
     * Index 0 = bytecode size, Index 1 = field count
     */
    private static final ConcurrentHashMap<String, int[]> LOADED_CLASSES =
        new ConcurrentHashMap<>();

    /**
     * Capture a loaded class with metrics. Called by ClassLoaderTransformer.
     *
     * @param className Internal class name format (com/foo/Bar)
     * @param bytecodeSize Size of class bytecode in bytes
     * @param fieldCount Number of fields in the class (-1 if unknown)
     */
    public static void capture(String className, int bytecodeSize, int fieldCount) {
        // Convert internal name (com/foo/Bar) to dotted (com.foo.Bar)
        String normalizedName = className.replace('/', '.');
        int[] metrics = new int[METRICS_LENGTH];
        metrics[IDX_BYTECODE_SIZE] = bytecodeSize;
        metrics[IDX_FIELD_COUNT] = fieldCount;
        LOADED_CLASSES.put(normalizedName, metrics);
    }

    /**
     * Get a copy of all loaded classes with metrics.
     *
     * @return Map of class name to metrics array [bytecodeSize, fieldCount]
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
     * Clear all captured classes.
     */
    public static void clear() {
        LOADED_CLASSES.clear();
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
}
