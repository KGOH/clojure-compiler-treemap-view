(ns clojure-compiler-treemap-view.require-agent
  "Validates that the metrics agent is loaded.

   This namespace must be required before agent.clj to provide
   a helpful error message instead of ClassNotFoundException.")

(defn- agent-loaded? []
  (try
    (Class/forName "clojure.metrics.MetricsBridge")
    true
    (catch ClassNotFoundException _
      false)))

(when-not (agent-loaded?)
  (throw (ex-info
           (str "Metrics agent not loaded.\n\n"
                "Start your REPL with:\n"
                "  clj -M:agent\n\n"
                "Or add the agent manually:\n"
                "  clj -J-javaagent:metrics-agent/target/metrics-agent.jar")
           {:type :agent-not-loaded})))
