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
  (:import [clojure.metrics MetricsBridge ClassLoadBridge VarRefBridge]))

;; ==========================================================================
;; Def Capture (via MetricsBridge)
;; ==========================================================================

(defn- extract-def-info
  "Extract def info from captured form data.
   Java hook provides: form, phase, ns, compiler-line
   We extract: op, name, line, end-line from the form."
  [raw-capture]
  (let [m (into {} (for [[k v] raw-capture] [(keyword k) v]))
        form (:form m)
        form-meta (meta form)
        name-sym (second form)]
    {:form form  ;; Keep form for metrics computation and unused detection
     :phase (:phase m)
     :ns (:ns m)
     :op (str (first form))
     :name (str name-sym)
     :line (or (:line form-meta)
               (:line (meta name-sym))
               (:compiler-line m))
     :end-line (or (:end-line form-meta)
                   (:end-line (meta name-sym)))}))

(defn get-captured-defs
  "Drain and return all captured def forms from the agent buffer.

   Returns a vector of maps, each containing:
     :form     - The captured form (Clojure data structure)
     :phase    - \"raw\" (pre-expansion) or \"expanded\" (post-expansion)
     :op       - The def type (\"def\", \"defn\", \"defn-\", \"defmacro\", \"defmulti\")
     :name     - The var name (string)
     :ns       - The namespace (string)
     :line     - Line number where def starts (may be nil)
     :end-line - Line number where def ends (may be nil)

   This clears the buffer - subsequent calls return only newly captured defs."
  []
  (mapv extract-def-info (MetricsBridge/drainBuffer)))

(defn peek-captured-defs
  "Peek at captured def forms without clearing the buffer.
   Returns the same format as get-captured-defs."
  []
  (mapv extract-def-info (MetricsBridge/peekBuffer)))

(defn buffer-size
  "Return the number of captured defs waiting in the buffer."
  []
  (MetricsBridge/bufferSize))

(defn clear!
  "Clear all captured defs from the buffer."
  []
  (MetricsBridge/clearBuffer))

(defn set-enabled!
  "Enable or disable def capturing."
  [enabled?]
  (MetricsBridge/setEnabled (boolean enabled?)))

(defn enabled?
  "Check if def capturing is currently enabled."
  []
  (MetricsBridge/isEnabled))

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

;;; ==========================================================================
;;; Class Loader Functions (Runtime Footprint)
;;; ==========================================================================

(defn get-loaded-classes
  "Return map of class-name -> bytecode-size for all captured classes.

   Only includes classes that passed the filter (excludes JDK classes)."
  []
  (into {} (ClassLoadBridge/getLoadedClasses)))

(defn loaded-class-count
  "Return the number of captured classes."
  []
  (ClassLoadBridge/classCount))

(defn total-bytecode-size
  "Return total bytecode size of all captured classes in bytes."
  []
  (ClassLoadBridge/totalBytecodeSize))

(defn clear-loaded-classes!
  "Clear all captured class data."
  []
  (ClassLoadBridge/clear))

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
     :java-bytes (reduce + 0 (map second java-classes))}))

(defn classes-by-namespace
  "Group loaded classes by namespace.

   Returns a map of namespace -> [{:class \"...\" :fn \"...\" :size N} ...]"
  []
  (let [classes (get-loaded-classes)]
    (->> classes
         (filter (fn [[k _]] (clojure-class? k)))
         (map (fn [[class-name size]]
                (assoc (parse-clojure-class class-name)
                       :class class-name
                       :size size)))
         (group-by :ns))))

(defn largest-classes
  "Return the N largest classes by bytecode size.

   Each entry is {:class \"...\" :size N :parsed {...}}"
  ([] (largest-classes 20))
  ([n]
   (->> (get-loaded-classes)
        (map (fn [[class-name size]]
               {:class class-name
                :size size
                :parsed (parse-clojure-class class-name)}))
        (sort-by :size >)
        (take n))))

;;; ==========================================================================
;;; Unused Var Detection (via compiler hooks)
;;; ==========================================================================

(defn get-var-references
  "Return set of all captured var references as strings \"ns/name\".
   These are vars that were actually referenced during compilation."
  []
  (into #{} (VarRefBridge/getReferences)))

(defn clear-var-references!
  "Clear all captured var references."
  []
  (VarRefBridge/clear))

(defn var-reference-count
  "Return the number of captured var references."
  []
  (VarRefBridge/size))

(defn find-unused-vars
  "Find unused vars in the given namespaces using compiler hooks.

   This uses the VarExpr/TheVarExpr hooks to capture all var references
   during compilation, then compares with ns-interns to find unused defs.

   Returns set of unused var symbols (qualified: ns/name).

   Note: Must be called AFTER loading the namespaces with the agent enabled.
   Use capture-namespace or capture-namespaces to load and capture in one step."
  [ns-syms]
  (let [;; Get all defined vars from runtime
        all-defs (into #{}
                       (for [ns-sym ns-syms
                             :let [ns-obj (find-ns ns-sym)]
                             :when ns-obj
                             [sym _] (ns-interns ns-obj)]
                         (str ns-sym "/" sym)))
        ;; Get all referenced vars from hooks
        all-refs (get-var-references)]
    (set/difference all-defs all-refs)))

(defn capture-namespaces
  "Clear buffers, load namespaces, and return captured defs.

   This clears both the def buffer and var reference buffer,
   loads the namespaces, then returns the captured def info.

   After calling this, use find-unused-vars to get unused vars."
  [ns-syms]
  (clear!)
  (clear-var-references!)
  (doseq [ns-sym ns-syms]
    (require ns-sym :reload))
  (get-captured-defs))

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

  ;; Unused var detection via hooks
  ;; 1. Load namespaces with agent enabled
  (capture-namespaces '[clojure-compiler-treemap-view.fixtures.alpha clojure-compiler-treemap-view.analyze-test])

  ;; 2. Find unused vars
  (find-unused-vars '[clojure-compiler-treemap-view.fixtures.alpha clojure-compiler-treemap-view.analyze-test])

  ,)
