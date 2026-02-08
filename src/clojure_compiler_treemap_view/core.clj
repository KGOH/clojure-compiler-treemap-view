(ns clojure-compiler-treemap-view.core
  "Public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [clojure-compiler-treemap-view.prometheus :as prom]))

(def analyze-captured
  "Analyze already-captured def forms without reloading namespaces.

   Drains the capture buffer (subsequent calls return empty until more forms
   compile). Store the result if you need it later.

   Useful when forms were captured during normal REPL usage.

   Options:
     :ns-syms - Optional collection of namespace symbols to filter by.
                If nil, processes all captured defs.

   Returns {:result {:compiler [...] :classloader [...]} :errors [...]}.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  cctv.analyze/analyze-captured)

(def analyze-nses
  "Analyze multiple namespaces using compiler hooks.

   This clears all capture buffers (defs, var refs, classes), loads all
   namespaces (in order), then processes captured data.

   Returns {:result {:compiler [...] :classloader [...]} :errors [...]} where:
     :compiler   - seq of function data maps, each with :unused? in metrics
     :classloader - seq of class data maps with :bytecode-size metric
     :errors     - vector of error maps from this analysis run

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  cctv.analyze/analyze-nses)

(defn write-metrics
  "Write metrics data in Prometheus format.

   metrics-data should be a map with :compiler and :classloader keys.
   path is the output file path.

   Returns the absolute path written."
  [metrics-data path]
  (prom/write-prometheus metrics-data path))

(comment
  ;; Using captured forms (after loading with agent)
  (def analyzed (analyze-captured))
  (def metrics-path (write-metrics (:result analyzed) "metrics.prom"))

  ;; Using analyze-nses (reloads namespaces)
  (def analyzed (analyze-nses (->> (all-ns) (map ns-name))))
  (def metrics-path (write-metrics (:result analyzed) "metrics.prom"))

  ;; Open viewer in browser, then drag & drop the .prom file
  (clojure.java.browse/browse-url (str "file://" (.getAbsolutePath (java.io.File. "viewer.html"))))

  ,)
