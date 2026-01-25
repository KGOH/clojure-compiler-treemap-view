(ns clojure-compiler-treemap-view.core
  "Public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [jsonista.core :as json]))

(defn export-metrics
  "Export metrics to a JSON file.

   Analyzes the given namespaces and writes the results to path.
   The JSON file can be loaded into the treemap viewer via:
   - File upload in the viewer UI
   - ?data=URL query param
   - Placing metrics.json alongside viewer.html

   Returns {:file \"path\" :errors [...]}.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-syms path]
  (let [{:keys [result errors]} (cctv.analyze/analyze-nses ns-syms)
        output {:version 1
                :generated (.toString (java.time.Instant/now))
                :namespaces (mapv str ns-syms)
                :compiler (:compiler result)
                :classloader (:classloader result)}]
    (spit path (json/write-value-as-string output))
    {:file path
     :errors errors}))


(comment
  (export-metrics '[clojure-compiler-treemap-view.analyze] "metrics.json")
  ,)
