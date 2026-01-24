(ns clojure-compiler-treemap-view.analyze
  "Namespace analysis and metrics extraction using compiler hooks.

   This namespace provides functions to analyze Clojure namespaces by
   capturing def forms during compilation via the metrics agent. It
   extracts metrics like lines of code, expression counts, nesting depth,
   and identifies unused vars."
  (:require [clojure-compiler-treemap-view.agent :as agent]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const phase-raw "raw")
(def ^:const phase-expanded "expanded")

;; ============================================================================
;; Error Tracking
;; ============================================================================

(def ^:private ^:dynamic *errors*
  "Dynamic var for collecting errors during analysis.
   Bound to a fresh atom at the start of each analysis operation."
  nil)

(defn- record-error! [ns-sym phase exception]
  (when *errors*
    (swap! *errors* conj {:ns ns-sym
                          :phase phase
                          :error exception
                          :message (.getMessage exception)
                          :timestamp (System/currentTimeMillis)})))

(defn- with-error-tracking*
  "Execute f with error tracking, returning {:result ... :errors [...]}."
  [f]
  (binding [*errors* (atom [])]
    (let [result (f)]
      {:result result
       :errors @*errors*})))

(defmacro ^:private with-error-tracking
  "Execute body with error tracking, returning {:result ... :errors [...]}."
  [& body]
  `(with-error-tracking* (fn [] ~@body)))

;; ============================================================================
;; Capture Coordination
;; ============================================================================

(defmacro with-capture
  "Clear capture buffers, execute body, then drain captured defs.
   Options (as leading keyword-value pairs before body):
     :include-var-refs? - Also clear var reference tracking (default: false)
   Binds `captured` to the result of get-captured-defs in body's scope.

   WARNING: Not thread-safe. Uses global buffers shared across all threads.
   Concurrent calls will corrupt each other's data."
  {:arglists '([& body] [:include-var-refs? bool & body])}
  [& args]
  (let [[opts body] (if (keyword? (first args))
                      [(apply hash-map (take 2 args)) (drop 2 args)]
                      [{} args])
        include-var-refs? (:include-var-refs? opts)]
    `(do
       (agent/clear!)
       ~(when include-var-refs?
          `(agent/clear-var-references!))
       ~@(butlast body)
       (let [~'captured (agent/get-captured-defs)]
         ~(last body)))))

;; ============================================================================
;; S-Expression Counters
;; ============================================================================

(defn count-sexp-forms
  "Count all forms in an s-expression recursively."
  [form]
  (cond
    (seq? form)    (reduce + 1 (map count-sexp-forms form))
    (vector? form) (reduce + 1 (map count-sexp-forms form))
    (map? form)    (reduce + 1 (map count-sexp-forms (mapcat identity form)))
    (set? form)    (reduce + 1 (map count-sexp-forms form))
    :else          1))

(defn sexp-max-depth
  "Maximum nesting depth of an s-expression."
  [form]
  (cond
    (sequential? form) (inc (reduce max 0 (map sexp-max-depth form)))
    (map? form)        (inc (reduce max 0 (map sexp-max-depth (mapcat identity form))))
    (set? form)        (inc (reduce max 0 (map sexp-max-depth form)))
    :else              0))

;; ============================================================================
;; Metrics from Captured Forms
;; ============================================================================

(defn- def-metrics-from-forms
  "Compute metrics from raw+expanded form pair.

   Raw form is the original source before macro expansion.
   Expanded form is after macro expansion (but still an s-expression, not AST)."
  [raw-form expanded-form]
  {:expressions-raw (if raw-form (count-sexp-forms raw-form) 0)
   :expressions-expanded (if expanded-form (count-sexp-forms expanded-form) 0)
   :max-depth-raw (if raw-form (sexp-max-depth raw-form) 0)
   :max-depth-expanded (if expanded-form (sexp-max-depth expanded-form) 0)})

;; ============================================================================
;; Processing Captured Defs
;; ============================================================================

(defn- process-captured-defs
  "Transform captured def forms into analyzed metrics.

   Takes a sequence of captured defs (from agent/get-captured-defs) and
   optional set of namespace strings to filter by.

   Returns vector of maps with :name :ns :file :line :metrics"
  [captured ns-strs]
  (let [by-ns-and-name (group-by (juxt :ns :name) captured)]
    (vec (for [[[ns-str name] forms] by-ns-and-name
               :when (or (nil? ns-strs) (contains? ns-strs ns-str))
               :let [raw (first (filter #(= phase-raw (:phase %)) forms))
                     expanded (first (filter #(= phase-expanded (:phase %)) forms))]
               :when raw]
           {:name name
            :ns ns-str
            :file nil
            :line (:line raw)
            :metrics (def-metrics-from-forms
                       (:form raw)
                       (or (:form expanded) (:form raw)))}))))

(defn- add-unused-flags
  "Add :unused? flag to each fn-data based on unused-vars set."
  [fn-data-seq unused-vars]
  (mapv (fn [{:keys [ns name] :as fn-data}]
          (let [full-name (str ns "/" name)
                unused? (contains? unused-vars full-name)]
            (assoc-in fn-data [:metrics :unused?] unused?)))
        fn-data-seq))

;; ============================================================================
;; Analyze Captured (no reload)
;; ============================================================================

(defn analyze-captured
  "Analyze already-captured def forms without reloading namespaces.

   Drains the capture buffer (subsequent calls return empty until more forms
   compile). Store the result if you need it later.

   Useful when forms were captured during normal REPL usage.

   Options:
     :ns-syms - Optional collection of namespace symbols to filter by.
                If nil, processes all captured defs.

   Returns {:result [...] :errors [...]}.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [& {:keys [ns-syms]}]
  (let [captured (agent/get-captured-defs)
        ns-strs (when ns-syms (set (map str ns-syms)))
        fn-data (process-captured-defs captured ns-strs)
        nses-to-check (or ns-syms
                          (->> fn-data (map :ns) distinct (map symbol)))
        unused-vars (agent/find-unused-vars nses-to-check)]
    {:result (add-unused-flags fn-data unused-vars)
     :errors []}))

;; ============================================================================
;; Namespace Analysis via Hooks (with reload)
;; ============================================================================

(defn analyze-ns
  "Analyze a single namespace using compiler hooks.

   Clears the capture buffer, reloads the namespace (triggering compilation),
   then processes the captured def forms into metrics.

   Returns {:result [...] :errors [...]} where:
     :result - vector of fn-data maps
     :errors - vector of error maps from this analysis run

   Each result entry contains:
     :name    - Var name (string)
     :ns      - Namespace (string)
     :file    - Source file (nil - hooks don't capture this)
     :line    - Line number
     :metrics - Map of metrics

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-sym]
  (with-error-tracking
    (try
      (with-capture
        (require ns-sym :reload)
        (process-captured-defs captured #{(str ns-sym)}))
      (catch Throwable e
        (record-error! ns-sym :analyze e)
        []))))

(defn analyze-nses
  "Analyze multiple namespaces using compiler hooks.

   This clears both capture buffers, loads all namespaces (in order),
   then processes captured defs and marks unused vars.

   Returns {:result [...] :errors [...]} where:
     :result - seq of function data maps, each with :unused? in metrics
     :errors - vector of error maps from this analysis run

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-syms]
  (with-error-tracking
    (with-capture :include-var-refs? true
      (doseq [ns-sym ns-syms]
        (try
          (require ns-sym :reload)
          (catch Throwable e
            (record-error! ns-sym :reload e))))
      (let [ns-strs (set (map str ns-syms))
            fn-data (process-captured-defs captured ns-strs)
            unused-vars (agent/find-unused-vars ns-syms)]
        (add-unused-flags fn-data unused-vars)))))

;; ============================================================================
;; Hierarchy Building
;; ============================================================================

(defn ns->path
  "Convert namespace string to path segments.
   \"foo.bar.baz\" -> [\"foo\" \"bar\" \"baz\"]"
  [ns-str]
  (str/split ns-str #"\."))

(defn- add-to-map-tree
  "Add a leaf to a nested map structure. O(1) per level."
  [tree path ns-str metrics]
  (if (= 1 (count path))
    (assoc tree (first path) {:leaf? true :ns ns-str :metrics metrics})
    (update tree (first path) (fnil add-to-map-tree {}) (rest path) ns-str metrics)))

(defn- map-tree->d3-tree
  "Convert nested map structure to D3-compatible tree with :name and :children."
  [name node]
  (if (:leaf? node)
    {:name name :ns (:ns node) :metrics (:metrics node)}
    {:name name
     :children (mapv (fn [[k v]] (map-tree->d3-tree k v)) node)}))

(defn build-hierarchy
  "Build D3-compatible hierarchy from flat function data.
   Complexity: O(n*d) where n = functions, d = average namespace depth.
   Input: seq of {:name \"fn\" :ns \"foo.bar\" :metrics {...}}
   Output: {:name \"root\" :children [{:name \"foo\" :children [...]}]}"
  [fn-data]
  (let [map-tree (reduce
                   (fn [tree {:keys [ns name metrics]}]
                     (let [path (conj (vec (ns->path ns)) name)]
                       (add-to-map-tree tree path ns metrics)))
                   {}
                   fn-data)]
    (map-tree->d3-tree "root" map-tree)))
