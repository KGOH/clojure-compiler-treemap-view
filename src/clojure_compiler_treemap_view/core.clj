(ns clojure-compiler-treemap-view.core
  "HTML generation and public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.java.browse :as browse]
            [clojure.string :as str]))

(defn- slurp-resource
  "Slurp a resource file from the resources directory."
  [filename]
  (slurp (io/resource filename)))

(defn render-html
  "Generate complete HTML string for treemap visualization.

   data-by-source: map of {:compiler [...] :classloader [...]}, or nil for static viewer.
   When data is provided, it's embedded as window.TREEMAP_DATA.
   When nil, viewer will try to load metrics.json or show upload UI."
  [data-by-source & {:keys [title]
                     :or {title "Code Metrics Treemap"}}]
  (let [data-json (if data-by-source
                    (json/write-value-as-string data-by-source)
                    "null")
        html-template (slurp-resource "treemap.html")
        d3 (slurp-resource "d3.v7.min.js")
        css (slurp-resource "treemap.css")
        js (slurp-resource "treemap.js")]
    (-> html-template
        (str/replace "{{TITLE}}" title)
        (str/replace "{{D3}}" d3)
        (str/replace "{{CSS}}" css)
        (str/replace "{{JS}}" js)
        (str/replace "{{DATA}}" data-json))))

(defn render-viewer
  "Generate static viewer HTML (no embedded data).
   The viewer will try to load metrics.json from same directory,
   or accept data via ?data=URL query param, or show upload UI."
  [& {:keys [title] :or {title "Code Metrics Treemap"}}]
  (render-html nil :title title))

(defn open-html
  "Write HTML to temp file and open in browser. Returns file path."
  [html]
  (let [html-file (java.io.File/createTempFile "treemap-" ".html")]
    (.deleteOnExit html-file)
    (spit html-file html)
    (browse/browse-url (str "file://" (.getAbsolutePath html-file)))
    (.getAbsolutePath html-file)))

(defn treemap!
  "Analyze namespaces and open treemap visualization.
   Convenience function combining analyze -> build-hierarchy -> render-html -> open.

   Returns {:file \"path\" :errors [...]}.

   The UI provides dropdowns to switch between data sources and metrics.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-syms]
  (let [{:keys [result errors]} (cctv.analyze/analyze-nses ns-syms)
        file-path (-> result render-html open-html)]
    {:file file-path
     :errors errors}))

(defn export-metrics
  "Export metrics to a JSON file.

   Analyzes the given namespaces and writes the results to path.
   The JSON file can be loaded into the treemap viewer via file upload.

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
  (def analysis (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.analyze]))
  (def analysis (cctv.analyze/analyze-captured))

  (:errors analysis)

  (-> analysis :result render-html open-html)

  ,)
