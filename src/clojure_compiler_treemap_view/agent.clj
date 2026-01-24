(ns clojure-compiler-treemap-view.agent
  "Clojure wrapper for the Java metrics agent.

   Requires the metrics-agent Java agent to be loaded via -javaagent flag.

   This namespace provides a Clojure-friendly interface to the Java agent,
   which has two instrumentation modes:

   1. Compiler Hook (MetricsBridge): captures def/defn forms as they compile
   2. Class Loader (ClassLoadBridge): captures all classes as they load

   Usage:
     ;; Start REPL with agent (both modes by default)
     clj -J-javaagent:metrics-agent/target/metrics-agent.jar

     ;; Or specific modes
     clj -J-javaagent:metrics-agent/target/metrics-agent.jar=compiler
     clj -J-javaagent:metrics-agent/target/metrics-agent.jar=classloader

     (require '[clojure-compiler-treemap-view.agent :as agent])

     ;; Compiler hook: captured defs
     (agent/get-captured-defs)

     ;; Class loader: runtime footprint
     (agent/runtime-footprint)"
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import [clojure.metrics MetricsBridge ClassLoadBridge]))

(defn agent-available?
  "Check if the metrics agent is loaded.
   Returns true if the MetricsBridge class is available."
  []
  (try
    (Class/forName "clojure.metrics.MetricsBridge")
    true
    (catch ClassNotFoundException _
      false)))

(defn get-captured-defs
  "Drain and return all captured def forms from the agent buffer.

   Returns a vector of maps, each containing:
     :op       - The def type (\"def\", \"defn\", \"defn-\", \"defmacro\", \"defmulti\")
     :name     - The var name (string)
     :ns       - The namespace (string)
     :line     - Line number where def starts (may be nil)
     :end-line - Line number where def ends (may be nil)

   This clears the buffer - subsequent calls return only newly captured defs."
  []
  (when (agent-available?)
    (->> (MetricsBridge/drainBuffer)
         (mapv (fn [m]
                 (into {} (for [[k v] m]
                            [(keyword k) v])))))))

(defn peek-captured-defs
  "Peek at captured def forms without clearing the buffer.
   Returns the same format as get-captured-defs."
  []
  (when (agent-available?)
    (->> (MetricsBridge/peekBuffer)
         (mapv (fn [m]
                 (into {} (for [[k v] m]
                            [(keyword k) v])))))))

(defn buffer-size
  "Return the number of captured defs waiting in the buffer."
  []
  (if (agent-available?)
    (MetricsBridge/bufferSize)
    0))

(defn clear!
  "Clear all captured defs from the buffer."
  []
  (when (agent-available?)
    (MetricsBridge/clearBuffer)))

(defn set-enabled!
  "Enable or disable def capturing."
  [enabled?]
  (when (agent-available?)
    (MetricsBridge/setEnabled (boolean enabled?))))

(defn enabled?
  "Check if def capturing is currently enabled."
  []
  (if (agent-available?)
    (MetricsBridge/isEnabled)
    false))

(defn capture-namespace
  "Clear buffer, load a namespace, and return captured defs.

   This is a convenience function for testing the agent.

   Usage:
     (capture-namespace 'my.namespace)
     ;; => [{:op \"defn\" :name \"foo\" :ns \"my.namespace\" :line 5} ...]"
  [ns-sym]
  (clear!)
  (require ns-sym :reload)
  (get-captured-defs))

(defn compare-with-analyzer
  "Compare agent-captured defs with tools.analyzer results.

   Returns a map with:
     :agent-only   - Defs found by agent but not analyzer
     :analyzer-only - Defs found by analyzer but not agent
     :both         - Defs found by both
     :line-mismatches - Defs where line numbers differ

   Requires clojure-compiler-treemap-view.analyze to be loaded."
  [ns-sym]
  (require 'clojure-compiler-treemap-view.analyze)
  (let [analyze-ns (ns-resolve 'clojure-compiler-treemap-view.analyze 'analyze-ns)
        agent-defs (capture-namespace ns-sym)
        analyzer-result (analyze-ns ns-sym)
        analyzer-defs (:analyzed analyzer-result)

        agent-names (set (map :name agent-defs))
        analyzer-names (set (map :name analyzer-defs))

        both (set/intersection agent-names analyzer-names)
        agent-only (set/difference agent-names analyzer-names)
        analyzer-only (set/difference analyzer-names agent-names)

        ;; Check for line number mismatches
        agent-by-name (into {} (map (juxt :name identity) agent-defs))
        analyzer-by-name (into {} (map (juxt :name identity) analyzer-defs))
        line-mismatches (for [name both
                              :let [agent-line (:line (agent-by-name name))
                                    analyzer-line (:line (analyzer-by-name name))]
                              :when (and agent-line analyzer-line
                                         (not= agent-line analyzer-line))]
                          {:name name
                           :agent-line agent-line
                           :analyzer-line analyzer-line})]

    {:agent-only agent-only
     :analyzer-only analyzer-only
     :both both
     :line-mismatches (vec line-mismatches)}))

;;; ==========================================================================
;;; Class Loader Functions (Runtime Footprint)
;;; ==========================================================================

(defn classloader-available?
  "Check if the class loader instrumentation is available."
  []
  (try
    (Class/forName "clojure.metrics.ClassLoadBridge")
    true
    (catch ClassNotFoundException _
      false)))

(defn get-loaded-classes
  "Return map of class-name -> bytecode-size for all captured classes.

   Only includes classes that passed the filter (excludes JDK classes)."
  []
  (when (classloader-available?)
    (into {} (ClassLoadBridge/getLoadedClasses))))

(defn loaded-class-count
  "Return the number of captured classes."
  []
  (if (classloader-available?)
    (ClassLoadBridge/classCount)
    0))

(defn total-bytecode-size
  "Return total bytecode size of all captured classes in bytes."
  []
  (if (classloader-available?)
    (ClassLoadBridge/totalBytecodeSize)
    0))

(defn clear-loaded-classes!
  "Clear all captured class data."
  []
  (when (classloader-available?)
    (ClassLoadBridge/clear)))

(defn clojure-class?
  "Check if class name matches Clojure function pattern (namespace$fn).

   Clojure compiles functions to classes with names like:
     clojure.core$map
     my.namespace$my_fn
     my.namespace$handler$fn__1234 (anonymous)"
  [class-name]
  (and (string? class-name)
       (str/includes? class-name "$")
       ;; Exclude Java inner classes which use $$ or have numeric-only suffix
       (not (str/includes? class-name "$$"))
       ;; Must have something before the $
       (not (str/starts-with? class-name "$"))))

(defn parse-clojure-class
  "Parse a Clojure class name into namespace and function name.

   'my.namespace$my_fn' -> {:ns \"my.namespace\" :fn \"my-fn\"}
   'my.namespace$handler$fn__1234' -> {:ns \"my.namespace\" :fn \"handler\" :anonymous? true}"
  [class-name]
  (when (clojure-class? class-name)
    (let [[ns-part & fn-parts] (str/split class-name #"\$")
          fn-part (first fn-parts)
          ;; Convert underscores back to hyphens (Clojure munging)
          fn-name (when fn-part (str/replace fn-part "_" "-"))
          anonymous? (or (> (count fn-parts) 1)
                        (and fn-name (re-matches #".*__\d+$" fn-name)))]
      (when (and ns-part fn-name)
        (cond-> {:ns ns-part
                 :fn (str/replace fn-name #"__\d+$" "")}
          anonymous? (assoc :anonymous? true))))))

(defn runtime-footprint
  "Get runtime footprint summary.

   Returns a map with:
     :total-classes   - Total number of captured classes
     :total-bytes     - Total bytecode size
     :total-mb        - Total bytecode size in MB
     :clojure-classes - Number of Clojure function classes
     :clojure-bytes   - Bytecode size of Clojure classes
     :java-classes    - Number of non-Clojure classes
     :java-bytes      - Bytecode size of non-Clojure classes"
  []
  (when (classloader-available?)
    (let [classes (get-loaded-classes)
          total-size (reduce + 0 (vals classes))
          clj-classes (filter (fn [[k _]] (clojure-class? k)) classes)
          java-classes (remove (fn [[k _]] (clojure-class? k)) classes)]
      {:total-classes (count classes)
       :total-bytes total-size
       :total-mb (/ total-size 1024.0 1024.0)
       :clojure-classes (count clj-classes)
       :clojure-bytes (reduce + 0 (map second clj-classes))
       :java-classes (count java-classes)
       :java-bytes (reduce + 0 (map second java-classes))})))

(defn classes-by-namespace
  "Group loaded classes by namespace.

   Returns a map of namespace -> [{:class \"...\" :fn \"...\" :size N} ...]"
  []
  (when (classloader-available?)
    (let [classes (get-loaded-classes)]
      (->> classes
           (filter (fn [[k _]] (clojure-class? k)))
           (map (fn [[class-name size]]
                  (assoc (parse-clojure-class class-name)
                         :class class-name
                         :size size)))
           (group-by :ns)))))

(defn largest-classes
  "Return the N largest classes by bytecode size.

   Each entry is {:class \"...\" :size N :parsed {...}}"
  ([] (largest-classes 20))
  ([n]
   (when (classloader-available?)
     (->> (get-loaded-classes)
          (map (fn [[class-name size]]
                 {:class class-name
                  :size size
                  :parsed (parse-clojure-class class-name)}))
          (sort-by :size >)
          (take n)))))

(comment

  ;; Compiler hook examples
  (def captured (get-captured-defs))

  (->> captured
       (map :ns)
       distinct
       sort)

  ;; Class loader examples
  (runtime-footprint)

  (largest-classes 10)

  (->> (classes-by-namespace)
       (map (fn [[ns classes]]
              [ns (reduce + 0 (map :size classes))]))
       (sort-by second >)
       (take 10))

  ,)
