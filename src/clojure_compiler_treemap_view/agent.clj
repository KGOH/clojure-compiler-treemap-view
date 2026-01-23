(ns clojure-compiler-treemap-view.agent
  "Clojure wrapper for the Java metrics agent.

   Requires the metrics-agent Java agent to be loaded via -javaagent flag.

   This namespace provides a Clojure-friendly interface to the MetricsBridge
   Java class, which receives captured def/defn forms from the compiler
   instrumentation agent.

   Usage:
     ;; Start REPL with agent: clj -J-javaagent:metrics-agent/target/metrics-agent.jar

     (require '[clojure-compiler-treemap-view.agent :as agent])

     ;; Clear any defs captured during startup
     (agent/clear!)

     ;; Load a namespace (this triggers compilation)
     (require '[some.namespace] :reload)

     ;; Get captured defs
     (agent/get-captured-defs)
     ;; => [{:op \"defn\" :name \"my-fn\" :ns \"some.namespace\" :line 5} ...]"
  (:require [clojure.set :as set])
  (:import [clojure.metrics MetricsBridge]))

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

(comment

  (def captured (get-captured-defs))

  (->> captured
       (map :ns)
       distinct
       sort)

  ,)
