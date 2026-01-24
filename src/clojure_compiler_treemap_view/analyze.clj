(ns clojure-compiler-treemap-view.analyze
  "Namespace analysis and metrics extraction using compiler hooks.

   This namespace provides functions to analyze Clojure namespaces by
   capturing def forms during compilation via the metrics agent. It
   extracts metrics like lines of code, expression counts, nesting depth,
   and identifies unused vars."
  (:require [clojure-compiler-treemap-view.agent :as agent]
            [clojure.string :as str]))

;; ============================================================================
;; Error Tracking
;; ============================================================================

(defonce errors
  ;; Atom containing analysis errors for debugging.
  ;; Each entry: {:ns namespace :phase :analyze|:unused-detection :error exception :timestamp ms}
  (atom []))

(defn clear-errors! []
  (reset! errors []))

(defn- record-error! [ns-sym phase exception]
  (swap! errors conj {:ns ns-sym
                      :phase phase
                      :error exception
                      :message (.getMessage exception)
                      :timestamp (System/currentTimeMillis)}))

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
  [raw-form expanded-form line end-line]
  (let [loc (if (and line end-line)
              (inc (- end-line line))
              1)]
    {:loc loc
     :expressions-raw (if raw-form (count-sexp-forms raw-form) 0)
     :expressions-expanded (if expanded-form (count-sexp-forms expanded-form) 0)
     :max-depth-raw (if raw-form (sexp-max-depth raw-form) 0)
     :max-depth-expanded (if expanded-form (sexp-max-depth expanded-form) 0)}))

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
               :let [raw (first (filter #(= "raw" (:phase %)) forms))
                     expanded (first (filter #(= "expanded" (:phase %)) forms))]
               :when raw]
           {:name name
            :ns ns-str
            :file nil
            :line (:line raw)
            :metrics (def-metrics-from-forms
                       (:form raw)
                       (or (:form expanded) (:form raw))
                       (:line raw)
                       (:end-line raw))}))))

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

   Peeks at the agent's capture buffer (does not drain it).
   Useful when forms were captured during normal REPL usage.
   Call agent/clear! explicitly when you want to reset the buffer.

   Options:
     :ns-syms - Optional collection of namespace symbols to filter by.
                If nil, processes all captured defs.
     :unused? - If true, adds :unused? flags using current var references.
                Requires namespaces to already be loaded.

   Returns vector of fn-data maps (same format as analyze-nses)."
  [& {:keys [ns-syms unused?]
      :or {unused? false}}]
  (let [captured (agent/peek-captured-defs)
        ns-strs (when ns-syms (set (map str ns-syms)))
        fn-data (process-captured-defs captured ns-strs)]
    (if unused?
      (let [nses-to-check (or ns-syms
                              (->> fn-data (map :ns) distinct (map symbol)))
            unused-vars (agent/find-unused-vars nses-to-check)]
        (add-unused-flags fn-data unused-vars))
      fn-data)))

;; ============================================================================
;; Namespace Analysis via Hooks (with reload)
;; ============================================================================

(defn analyze-ns
  "Analyze a single namespace using compiler hooks.

   Clears the capture buffer, reloads the namespace (triggering compilation),
   then processes the captured def forms into metrics.

   Returns {:analyzed [...] :asts []}
   Note: :asts is always empty (maintained for API compatibility).

   Each analyzed entry contains:
     :name    - Var name (string)
     :ns      - Namespace (string)
     :file    - Source file (nil - hooks don't capture this)
     :line    - Line number
     :metrics - Map of metrics"
  [ns-sym]
  (try
    (agent/clear!)
    (require ns-sym :reload)
    (let [captured (agent/get-captured-defs)
          ns-strs #{(str ns-sym)}]
      {:analyzed (process-captured-defs captured ns-strs)
       :asts []})
    (catch Throwable e
      (record-error! ns-sym :analyze e)
      {:analyzed [] :asts []})))

(defn analyze-nses
  "Analyze multiple namespaces using compiler hooks.

   This clears both capture buffers, loads all namespaces (in order),
   then processes captured defs and marks unused vars.

   Returns seq of function data maps, each with :unused? in metrics."
  [ns-syms]
  (agent/clear!)
  (agent/clear-var-references!)
  (doseq [ns-sym ns-syms]
    (try
      (require ns-sym :reload)
      (catch Throwable e
        (record-error! ns-sym :reload e))))
  (let [captured (agent/get-captured-defs)
        ns-strs (set (map str ns-syms))
        fn-data (process-captured-defs captured ns-strs)
        unused-vars (agent/find-unused-vars ns-syms)]
    (add-unused-flags fn-data unused-vars)))

;; ============================================================================
;; Hierarchy Building
;; ============================================================================

(defn ns->path
  "Convert namespace string to path segments.
   \"foo.bar.baz\" -> [\"foo\" \"bar\" \"baz\"]"
  [ns-str]
  (str/split ns-str #"\."))

(defn- ensure-path
  "Ensure path exists in tree, creating intermediate nodes as needed."
  [tree path]
  (if (empty? path)
    tree
    (let [[segment & rest-path] path
          children (:children tree [])
          existing-idx (first (keep-indexed
                                (fn [i child]
                                  (when (= (:name child) segment) i))
                                children))]
      (if existing-idx
        (update-in tree [:children existing-idx] ensure-path rest-path)
        (let [new-child (ensure-path {:name segment :children []} rest-path)]
          (update tree :children (fnil conj []) new-child))))))

(defn- add-leaf
  "Add a leaf node (function) at the given path."
  [tree path metrics ns-str]
  (if (= 1 (count path))
    ;; Add leaf with full namespace
    (update tree :children (fnil conj [])
            {:name (first path) :ns ns-str :metrics metrics})
    ;; Navigate deeper
    (let [[segment & rest-path] path
          children (:children tree [])
          idx (first (keep-indexed
                       (fn [i child]
                         (when (= (:name child) segment) i))
                       children))]
      (if idx
        (update-in tree [:children idx] add-leaf rest-path metrics ns-str)
        ;; Create path if not exists
        (let [new-tree (ensure-path tree [segment])]
          (add-leaf new-tree path metrics ns-str))))))

(defn build-hierarchy
  "Build D3-compatible hierarchy from flat function data.
   Input: seq of {:name \"fn\" :ns \"foo.bar\" :metrics {...}}
   Output: {:name \"root\" :children [{:name \"foo\" :children [...]}]}"
  [fn-data]
  (reduce
    (fn [tree {:keys [ns name metrics]}]
      (let [path (conj (vec (ns->path ns)) name)]
        (add-leaf tree path metrics ns)))
    {:name "root" :children []}
    fn-data))
