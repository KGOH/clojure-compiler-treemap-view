(ns clojure-compiler-treemap-view.core
  "Public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [jsonista.core :as json]))

(def ^:private pretty-mapper
  (json/object-mapper {:pretty true}))

(defn write-metrics
  "Write metrics data to a JSON file.

   metrics-data should be a map with :compiler and :classloader keys.
   ns-syms is a list of namespace symbols for metadata.
   path is the output file path.

   Returns the path written."
  [metrics-data ns-syms path]
  (let [output {:version 1
                :generated (.toString (java.time.Instant/now))
                :namespaces (mapv str ns-syms)
                :compiler (:compiler metrics-data)
                :classloader (:classloader metrics-data)}]
    (spit path (json/write-value-as-string output pretty-mapper))
    path))

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
  (let [{:keys [result errors]} (cctv.analyze/analyze-nses ns-syms)]
    {:file (write-metrics result ns-syms path)
     :errors errors}))

(defn export-captured-metrics
  "Export metrics from already-captured forms to a JSON file.

   Use this after loading namespaces with the agent active.
   Returns {:file \"path\" :errors [...]}."
  [ns-syms path]
  (let [{:keys [result errors]} (cctv.analyze/analyze-captured)]
    {:file (write-metrics result ns-syms path)
     :errors errors}))


(comment
  (export-metrics '[clojure-compiler-treemap-view.analyze] "metrics.json")
  ,)
