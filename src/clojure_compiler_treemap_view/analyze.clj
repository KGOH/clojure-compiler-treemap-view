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

;; Forward declaration for class loading (defined later, used in analyze-nses)
(declare process-loaded-classes)

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
  "Clear all capture buffers, execute body, then drain captured defs.
   Binds `captured` to the result of get-captured-defs in body's scope.

   WARNING: Not thread-safe. Uses global buffers shared across all threads.
   Concurrent calls will corrupt each other's data."
  [& body]
  `(do
     (agent/clear!)
     (agent/clear-var-references!)
     (agent/clear-loaded-classes!)
     ~@(butlast body)
     (let [~'captured (agent/get-captured-defs)]
       ~(last body))))

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

   Returns {:result {:compiler [...] :classloader [...]} :errors [...]}.

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [& {:keys [ns-syms]}]
  (let [captured (agent/get-captured-defs)
        ns-strs (when ns-syms (set (map str ns-syms)))
        fn-data (process-captured-defs captured ns-strs)
        nses-to-check (or ns-syms
                          (->> fn-data (map :ns) distinct (map symbol)))
        unused-vars (agent/find-unused-vars nses-to-check)
        compiler-data (add-unused-flags fn-data unused-vars)
        ;; Class loader data (munge ns names: foo-bar -> foo_bar)
        ns-prefixes (when ns-syms
                      (set (map #(str/replace (str %) "-" "_") ns-syms)))
        classes (agent/get-loaded-classes)
        class-data (process-loaded-classes classes ns-prefixes)]
    {:result {:compiler compiler-data
              :classloader class-data}
     :errors []}))

;; ============================================================================
;; Namespace Analysis via Hooks (with reload)
;; ============================================================================

(defn analyze-nses
  "Analyze multiple namespaces using compiler hooks.

   This clears all capture buffers (defs, var refs, classes), loads all
   namespaces (in order), then processes captured data.

   Returns {:result {:compiler [...] :classloader [...]} :errors [...]} where:
     :compiler   - seq of function data maps, each with :unused? in metrics
     :classloader - seq of class data maps with :bytecode-size metric
     :errors     - vector of error maps from this analysis run

   WARNING: Not thread-safe. Do not call concurrently from multiple threads."
  [ns-syms]
  (with-error-tracking
    (with-capture
      (doseq [ns-sym ns-syms]
        (try
          (require ns-sym :reload)
          (catch Throwable e
            (record-error! ns-sym :reload e))))
      (let [ns-strs (set (map str ns-syms))
            ;; Compiler data
            fn-data (process-captured-defs captured ns-strs)
            unused-vars (agent/find-unused-vars ns-syms)
            compiler-data (add-unused-flags fn-data unused-vars)
            ;; Class loader data (munge ns names: foo-bar -> foo_bar)
            ns-prefixes (set (map #(str/replace (str %) "-" "_") ns-syms))
            classes (agent/get-loaded-classes)
            class-data (process-loaded-classes classes ns-prefixes)]
        {:compiler compiler-data
         :classloader class-data}))))

;; ============================================================================
;; Hierarchy Building
;; ============================================================================

(defn ns->path
  "Convert namespace string to path segments.
   \"foo.bar.baz\" -> [\"foo\" \"bar\" \"baz\"]"
  [ns-str]
  (str/split ns-str #"\."))

(defn- add-to-map-tree
  "Add a node to a nested map structure. O(1) per level.
   Nodes can have both :metrics and :children (e.g. a function with closures).
   node-data is a map with :ns, :metrics, and optionally :full-name."
  [tree path node-data]
  (if (= 1 (count path))
    ;; Add/merge node-data onto existing node (which may already have children)
    (update tree (first path) merge node-data)
    ;; Recurse into :children
    (update-in tree [(first path) :children] (fnil add-to-map-tree {}) (rest path) node-data)))

(defn- map-tree->d3-tree
  "Convert nested map structure to D3-compatible tree with :name and :children.
   Nodes can have both :metrics and :children."
  [name node]
  (cond-> {:name name}
    (:ns node)        (assoc :ns (:ns node))
    (:full-name node) (assoc :full-name (:full-name node))
    (:metrics node)   (assoc :metrics (:metrics node))
    (:children node)  (assoc :children (mapv (fn [[k v]] (map-tree->d3-tree k v))
                                             (:children node)))))

(defn build-hierarchy
  "Build D3-compatible hierarchy from flat function data.
   Complexity: O(n*d) where n = functions, d = average namespace depth.
   Input: seq of {:name \"fn\" :ns \"foo.bar\" :metrics {...}}
   Output: {:name \"root\" :children [{:name \"foo\" :children [...]}]}"
  [fn-data]
  (let [map-tree (reduce
                   (fn [tree {:keys [ns name metrics]}]
                     (let [path (conj (vec (ns->path ns)) name)]
                       (add-to-map-tree tree path {:ns ns :metrics metrics})))
                   {}
                   fn-data)]
    (map-tree->d3-tree "root" {:children map-tree})))

;; ============================================================================
;; Class Loading Data
;; ============================================================================

(defn class-name->path
  "Convert Clojure class name to path segments for hierarchy.
   Splits on both '.' (namespace/package) and '$' (inner class/fn).
   \"foo.bar.baz$quux$fn__123\" -> [\"foo\" \"bar\" \"baz\" \"quux\" \"fn__123\"]"
  [class-name]
  (str/split class-name #"[.$]"))

(defn- process-loaded-classes
  "Transform loaded classes into fn-data format for hierarchy building.

   Takes map of {class-name -> bytecode-size} and set of namespace
   prefixes to filter by (munged, e.g. \"foo_bar\" not \"foo-bar\").

   Returns vector of maps with :name :ns :full-name :file :line :metrics"
  [classes ns-prefixes]
  (vec
    (for [[class-name bytecode-size] classes
          :let [path (class-name->path class-name)
                name (last path)]
          :when (or (nil? ns-prefixes)
                    (some #(str/starts-with? class-name %) ns-prefixes))]
      {:name name
       :ns nil
       :full-name class-name
       :file nil
       :line nil
       :metrics {:bytecode-size bytecode-size}})))

(defn build-class-hierarchy
  "Build D3-compatible hierarchy from class data.
   Reuses add-to-map-tree and map-tree->d3-tree from build-hierarchy.

   Input: seq of {:name \"fn__123\" :full-name \"foo.bar$baz$fn__123\" :metrics {:bytecode-size 1234}}
   Output: {:name \"root\" :children [{:name \"foo\" :children [...]}]}"
  [class-data]
  (let [map-tree (reduce
                   (fn [tree {:keys [name full-name metrics]}]
                     (let [path (class-name->path full-name)]
                       (add-to-map-tree tree path {:full-name full-name :metrics metrics})))
                   {}
                   class-data)]
    (map-tree->d3-tree "root" {:children map-tree})))
