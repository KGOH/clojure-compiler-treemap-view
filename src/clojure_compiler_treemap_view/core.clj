(ns clojure-compiler-treemap-view.core
  "HTML generation and public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.java.browse :as browse]
            [clojure.string :as str]))

(def ^:private source-configs
  "Configuration for each data source."
  {:compiler {:id "compiler"
              :label "Compiler"
              :metrics [{:key :expressions-raw :label "Expressions (Raw)"}
                        {:key :expressions-expanded :label "Expressions (Expanded)"}
                        {:key :max-depth-raw :label "Max Depth (Raw)"}
                        {:key :max-depth-expanded :label "Max Depth (Expanded)"}]
              :default-size :expressions-raw
              :default-color :max-depth-raw}
   :classloader {:id "classloader"
                 :label "Class Loader"
                 :metrics [{:key :bytecode-size :label "Bytecode Size (bytes)"}]
                 :default-size :bytecode-size
                 :default-color :bytecode-size}})

(defn- slurp-resource
  "Slurp a resource file from the resources directory."
  [filename]
  (slurp (io/resource filename)))

(defn render-html
  "Generate complete HTML string for treemap visualization.

   data-by-source: map of {:compiler [...] :classloader [...]}
   Each value is flat fn-data that will be converted to a hierarchy."
  [data-by-source & {:keys [default-source title]
                     :or {default-source :compiler
                          title "Code Metrics Treemap"}}]
  (let [;; Build sources array for JS
        sources (vec
                  (for [[source-key data] data-by-source
                        :when (seq data)
                        :let [config (get source-configs source-key)
                              tree (case source-key
                                     :compiler (cctv.analyze/build-hierarchy data)
                                     :classloader (cctv.analyze/build-class-hierarchy data))]]
                    {:id (:id config)
                     :label (:label config)
                     :tree tree
                     :metrics (mapv #(update % :key name) (:metrics config))
                     :defaultSize (name (:default-size config))
                     :defaultColor (name (:default-color config))}))
        sources-json (json/write-value-as-string sources)
        default-source-id (name default-source)
        html-template (slurp-resource "treemap.html")
        css (slurp-resource "treemap.css")
        js (slurp-resource "treemap.js")]
    (-> html-template
        (str/replace "{{TITLE}}" title)
        (str/replace "{{CSS}}" css)
        (str/replace "{{JS}}" js)
        (str/replace "{{SOURCES}}" sources-json)
        (str/replace "{{DEFAULT_SOURCE}}" default-source-id))))

(defn open-html
  "Write HTML and D3.js to temp directory and open in browser. Returns file path."
  [html]
  (let [html-file (java.io.File/createTempFile "treemap-" ".html")
        d3-file (java.io.File. (.getParent html-file) "d3.v7.min.js")]
    (.deleteOnExit html-file)
    (.deleteOnExit d3-file)
    (spit d3-file (slurp-resource "d3.v7.min.js"))
    (spit html-file html)
    (browse/browse-url (str "file://" (.getAbsolutePath html-file)))
    (.getAbsolutePath html-file)))

(defn treemap!
  "Analyze namespaces and open treemap visualization.
   Convenience function combining analyze -> build-hierarchy -> render-html -> open.

   Returns {:file \"path\" :errors [...]}.

   Options:
     :source - Default data source to show (:compiler or :classloader, default :compiler)

   The UI provides dropdowns to switch between data sources and metrics.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-syms & {:keys [source]
              :or {source :compiler}}]
  (let [{:keys [result errors]} (cctv.analyze/analyze-nses ns-syms)
        file-path (-> result
                      (render-html :default-source source)
                      open-html)]
    {:file file-path
     :errors errors}))


(comment
  (def analysis (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.analyze]))
  (def result (:result analysis))
  (def errors (:errors analysis))

  ;; result now has :compiler and :classloader keys
  (:compiler result)
  (:classloader result)

  ;; Build hierarchies
  (def compiler-tree (cctv.analyze/build-hierarchy (:compiler result)))
  (def class-tree (cctv.analyze/build-class-hierarchy (:classloader result)))

  ;; Open with all sources
  (open-html (render-html result))

  ,)
