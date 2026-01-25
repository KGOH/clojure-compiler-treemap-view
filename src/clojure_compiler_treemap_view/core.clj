(ns clojure-compiler-treemap-view.core
  "Public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [jsonista.core :as json]))

(def analyze-nses
  "Analyze namespaces by reloading them with the agent active.
   Returns {:result {:compiler [...] :classloader [...]} :errors [...]}."
  cctv.analyze/analyze-nses)

(def analyze-captured
  "Analyze already-captured forms from the agent buffer.
   Returns {:result {:compiler [...] :classloader [...]} :errors [...]}."
  cctv.analyze/analyze-captured)

(defn write-metrics
  "Write metrics data to a JSON file.

   metrics-data should be a map with :compiler and :classloader keys.
   path is the output file path.

   Returns the absolute path written."
  [metrics-data path]
  (let [output {:version     1
                :generated   (.toString (java.time.Instant/now))
                :compiler    (:compiler metrics-data)
                :classloader (:classloader metrics-data)}
        file (.getAbsolutePath (java.io.File. path))]
    (spit file (json/write-value-as-string output (json/object-mapper {:pretty true})))
    file))

(comment
  ;; Using captured forms (after loading with agent)
  (def analyzed (analyze-captured))
  (def metrics-path (write-metrics (:result analyzed) "metrics.json"))

  ;; Using analyze-nses (reloads namespaces)
  (def analyzed (analyze-nses (->> (all-ns) (map ns-name))))
  (def metrics-path (write-metrics (:result analyzed) "metrics.json"))

  ;; Open in browser
  (clojure.java.browse/browse-url (str "file://" (.getAbsolutePath (java.io.File. "viewer.html")) "?data=file://" metrics-path))

  ,)
