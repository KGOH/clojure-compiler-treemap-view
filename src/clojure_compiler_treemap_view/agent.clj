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
            [clojure.string :as str]))

;; ==========================================================================
;; Reflection helpers for optional agent classes
;; ==========================================================================

(defn- invoke-static
  "Invoke a static method on a class by name. Returns nil if class not found."
  [class-name method-name & args]
  (try
    (let [clazz (Class/forName class-name)
          ;; Find method matching arg count (simplified - assumes no overloading)
          methods (.getDeclaredMethods clazz)
          method (first (filter #(and (= method-name (.getName %))
                                      (= (count args) (count (.getParameterTypes %))))
                                methods))]
      (when method
        (.invoke method nil (into-array Object args))))
    (catch ClassNotFoundException _ nil)
    (catch Exception _ nil)))

(defn- metrics-bridge-call [method & args]
  (apply invoke-static "clojure.metrics.MetricsBridge" method args))

(defn- classload-bridge-call [method & args]
  (apply invoke-static "clojure.metrics.ClassLoadBridge" method args))

(defn- varref-bridge-call [method & args]
  (apply invoke-static "clojure.metrics.VarRefBridge" method args))

(defn agent-available?
  "Check if the metrics agent is loaded.
   Returns true if the MetricsBridge class is available."
  []
  (try
    (Class/forName "clojure.metrics.MetricsBridge")
    true
    (catch ClassNotFoundException _
      false)))

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
  (when (agent-available?)
    (mapv extract-def-info (metrics-bridge-call "drainBuffer"))))

(defn peek-captured-defs
  "Peek at captured def forms without clearing the buffer.
   Returns the same format as get-captured-defs."
  []
  (when (agent-available?)
    (mapv extract-def-info (metrics-bridge-call "peekBuffer"))))

(defn buffer-size
  "Return the number of captured defs waiting in the buffer."
  []
  (if (agent-available?)
    (or (metrics-bridge-call "bufferSize") 0)
    0))

(defn clear!
  "Clear all captured defs from the buffer."
  []
  (when (agent-available?)
    (metrics-bridge-call "clearBuffer")))

(defn set-enabled!
  "Enable or disable def capturing."
  [enabled?]
  (when (agent-available?)
    (metrics-bridge-call "setEnabled" (boolean enabled?))))

(defn enabled?
  "Check if def capturing is currently enabled."
  []
  (if (agent-available?)
    (boolean (metrics-bridge-call "isEnabled"))
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
    (into {} (classload-bridge-call "getLoadedClasses"))))

(defn loaded-class-count
  "Return the number of captured classes."
  []
  (if (classloader-available?)
    (or (classload-bridge-call "classCount") 0)
    0))

(defn total-bytecode-size
  "Return total bytecode size of all captured classes in bytes."
  []
  (if (classloader-available?)
    (or (classload-bridge-call "totalBytecodeSize") 0)
    0))

(defn clear-loaded-classes!
  "Clear all captured class data."
  []
  (when (classloader-available?)
    (classload-bridge-call "clear")))

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

;;; ==========================================================================
;;; Unused Var Detection (via compiler hooks)
;;; ==========================================================================

(defn varref-available?
  "Check if the var reference hook is available."
  []
  (try
    (Class/forName "clojure.metrics.VarRefBridge")
    true
    (catch ClassNotFoundException _
      false)))

(defn get-var-references
  "Return set of all captured var references as strings \"ns/name\".
   These are vars that were actually referenced during compilation."
  []
  (when (varref-available?)
    (into #{} (varref-bridge-call "getReferences"))))

(defn clear-var-references!
  "Clear all captured var references."
  []
  (when (varref-available?)
    (varref-bridge-call "clear")))

(defn var-reference-count
  "Return the number of captured var references."
  []
  (if (varref-available?)
    (or (varref-bridge-call "size") 0)
    0))

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

(defn compare-unused-detection
  "Compare hook-based unused detection with tools.analyzer.

   Returns map with:
     :hook-unused     - Unused vars found by hooks
     :analyzer-unused - Unused vars found by tools.analyzer
     :both            - Found by both (agreement)
     :hook-only       - Only hooks found (potential false positive)
     :analyzer-only   - Only analyzer found (missed by hooks)"
  [ns-syms]
  (require 'clojure-compiler-treemap-view.analyze)
  (let [ns-syms (if (sequential? ns-syms) ns-syms [ns-syms])

        ;; Capture with hooks
        _ (capture-namespaces ns-syms)
        hook-unused (find-unused-vars ns-syms)

        ;; Get tools.analyzer results
        analyze-nses (ns-resolve 'clojure-compiler-treemap-view.analyze 'analyze-nses)
        analyzer-result (analyze-nses ns-syms)
        analyzer-unused (into #{}
                              (comp (filter #(get-in % [:metrics :unused?]))
                                    (map #(str (:ns %) "/" (:name %))))
                              analyzer-result)

        both (set/intersection hook-unused analyzer-unused)
        hook-only (set/difference hook-unused analyzer-unused)
        analyzer-only (set/difference analyzer-unused hook-unused)]
    {:hook-unused hook-unused
     :analyzer-unused analyzer-unused
     :both both
     :hook-only hook-only
     :analyzer-only analyzer-only
     :match? (and (empty? hook-only) (empty? analyzer-only))}))

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
#_#{"clojure-compiler-treemap-view.fixtures.alpha/unused-fn"
    "clojure-compiler-treemap-view.fixtures.alpha/simple-fn"}


  ;; 3. Compare with tools.analyzer
  (compare-unused-detection '[clojure-compiler-treemap-view.fixtures.alpha clojure-compiler-treemap-view.analyze-test])

  ,)
