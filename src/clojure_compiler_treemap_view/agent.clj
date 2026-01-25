(ns clojure-compiler-treemap-view.agent
  "Clojure wrapper for the Java metrics agent.

   Requires the metrics-agent Java agent to be loaded via -javaagent flag.

   This namespace provides a Clojure-friendly interface to the Java agent's
   compiler hooks that capture def/defn forms and var references during compilation.

   Usage:
     ;; Start REPL with agent
     clj -J-javaagent:metrics-agent/target/metrics-agent.jar

     (require '[clojure-compiler-treemap-view.agent :as agent])

     ;; Get captured defs
     (agent/get-captured-defs)

     ;; Find unused vars
     (agent/find-unused-vars ns-syms)"
  (:require [clojure-compiler-treemap-view.require-agent] ;; Validates agent is loaded
            [clojure.set :as set])
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

(defn clear!
  "Clear all captured defs from the buffer."
  []
  (MetricsBridge/clearBuffer))

;;; ==========================================================================
;;; Class Loader Functions (Runtime Footprint)
;;; ==========================================================================

(defn get-loaded-classes
  "Return map of class-name -> bytecode-size for all captured classes.

   Only includes classes that passed the filter (excludes JDK classes)."
  []
  (into {} (ClassLoadBridge/getLoadedClasses)))

(defn clear-loaded-classes!
  "Clear all captured class data."
  []
  (ClassLoadBridge/clear))

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

(defn find-unused-vars
  "Find unused vars in the given namespaces using compiler hooks.

   This uses the VarExpr/TheVarExpr hooks to capture all var references
   during compilation, then compares with ns-interns to find unused defs.

   Returns set of unused var symbols (qualified: ns/name).

   Note: ^:const vars are tracked via the analyzeSymbol hook, which captures
   them before const inlining occurs.

   Note: Must be called AFTER loading the namespaces with the agent enabled."
  [ns-syms]
  (let [;; Get all defined vars from runtime
        all-defs (into #{}
                       (for [ns-sym ns-syms
                             :let [ns-obj (find-ns ns-sym)]
                             :when ns-obj
                             [sym _] (ns-interns ns-obj)]
                         (str ns-sym "/" sym)))
        ;; Get all referenced vars from hooks (includes const vars now)
        all-refs (get-var-references)]
    (set/difference all-defs all-refs)))

(comment

  ;; Captured defs
  (def captured (get-captured-defs))

  (->> captured
       (map :ns)
       distinct
       sort)

  ;; Unused var detection
  ;; Use analyze/analyze-nses which handles buffer clearing and unused detection
  ;; See clojure-compiler-treemap-view.analyze namespace

  ,)
