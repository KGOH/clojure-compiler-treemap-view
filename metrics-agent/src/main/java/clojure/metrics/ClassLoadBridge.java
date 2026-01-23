package clojure.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between the ClassLoaderTransformer and Clojure code.
 *
 * Stores class names and their bytecode sizes in a thread-safe map.
 *
 * Usage from Clojure:
 *   (import '[clojure.metrics ClassLoadBridge])
 *   (ClassLoadBridge/getLoadedClasses)  ; returns map of class-name -> size
 *   (ClassLoadBridge/totalBytecodeSize) ; returns total bytes
 */
public class ClassLoadBridge {

    /**
     * Map of class name (dotted format) to bytecode size in bytes.
     */
    private static final ConcurrentHashMap<String, Integer> LOADED_CLASSES =
        new ConcurrentHashMap<>();

    /**
     * Capture a loaded class. Called by ClassLoaderTransformer.
     *
     * @param className Internal class name format (com/foo/Bar)
     * @param bytecodeSize Size of class bytecode in bytes
     */
    public static void capture(String className, int bytecodeSize) {
        // Convert internal name (com/foo/Bar) to dotted (com.foo.Bar)
        String normalizedName = className.replace('/', '.');
        LOADED_CLASSES.put(normalizedName, bytecodeSize);
    }

    /**
     * Get a copy of all loaded classes.
     *
     * @return Map of class name to bytecode size
     */
    public static Map<String, Integer> getLoadedClasses() {
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
            .mapToLong(Integer::longValue)
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
        Integer size = LOADED_CLASSES.get(className);
        return size != null ? size : -1;
    }
}
