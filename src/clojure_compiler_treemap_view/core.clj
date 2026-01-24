(ns clojure-compiler-treemap-view.core
  "HTML generation and public API for clojure-compiler-treemap-view."
  (:require [clojure-compiler-treemap-view.analyze :as analyze]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.java.browse :as browse]
            [clojure.string :as str]))

(def ^:private metrics-options
  "Available metrics for size and color dropdowns."
  [{:key :expressions-raw :label "Expressions (Raw)"}
   {:key :expressions-expanded :label "Expressions (Expanded)"}
   {:key :max-depth-raw :label "Max Depth (Raw)"}
   {:key :max-depth-expanded :label "Max Depth (Expanded)"}])

(defn- slurp-resource
  "Slurp a resource file from the resources directory."
  [filename]
  (slurp (io/resource filename)))

(defn render-html
  "Generate complete HTML string for treemap visualization."
  [tree-data & {:keys [size color title]
                :or {size :expressions-raw
                     color :max-depth-raw
                     title "Code Metrics Treemap"}}]
  (let [data-json (json/write-value-as-string tree-data)
        options-json (json/write-value-as-string (mapv #(assoc % :key (name (:key %))) metrics-options))
        html-template (slurp-resource "treemap.html")
        css (slurp-resource "treemap.css")
        js (slurp-resource "treemap.js")]
    (-> html-template
        (str/replace "{{TITLE}}" title)
        (str/replace "{{CSS}}" css)
        (str/replace "{{JS}}" js)
        (str/replace "{{DATA}}" data-json)
        (str/replace "{{OPTIONS}}" options-json)
        (str/replace "{{DEFAULT_SIZE}}" (name size))
        (str/replace "{{DEFAULT_COLOR}}" (name color)))))

(defn open-html
  "Write HTML to temp file and open in browser. Returns file path."
  [html]
  (let [f (java.io.File/createTempFile "treemap-" ".html")]
    (.deleteOnExit f)
    (spit f html)
    (browse/browse-url (str "file://" (.getAbsolutePath f)))
    (.getAbsolutePath f)))

(defn treemap!
  "Analyze namespaces and open treemap visualization.
   Convenience function combining analyze -> build-hierarchy -> render-html -> open.

   Returns {:file \"path\" :errors [...]}.

   Options:
     :size  - Metric for cell size (default: :expressions-raw)
     :color - Metric for cell color (default: :max-depth-raw)

   Available metrics:
     :expressions-raw, :expressions-expanded, :max-depth-raw, :max-depth-expanded"
  [ns-syms & {:keys [size color]
              :or {size :expressions-raw
                   color :max-depth-raw}}]
  (let [{:keys [result errors]} (analyze/analyze-nses ns-syms)
        file-path (-> result
                      analyze/build-hierarchy
                      (render-html :size size :color color)
                      open-html)]
    {:file file-path
     :errors errors}))


(comment
  (def {:keys [result errors]} (analyze/analyze-captured))

  #_(def {:keys [result errors]} (analyze/analyze-nses (->> (all-ns) (map ns-name))))

  ;; Check errors from last analysis
  errors
  (->> errors (map :ns) distinct)

  (def tree (analyze/build-hierarchy result))

  (open-html (render-html tree))

  ,)
