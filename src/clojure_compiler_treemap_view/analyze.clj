(ns clojure-compiler-treemap-view.analyze
  "AST walking and metrics extraction for code visualization."
  (:require [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.ast :as ast]
            [clojure.set :as set]
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

(defn reset-analyzer!
  "Reset analyzer state to fix protocol cache invalidation.
   Call this if you get 'No implementation of method: :do-reflect' errors."
  []
  ;; Reload clojure.reflect to fix protocol dispatch cache
  (require 'clojure.reflect :reload)
  ;; Clear memoized reflection cache in tools.analyzer.jvm.utils
  (when-let [members-var (resolve 'clojure.tools.analyzer.jvm.utils/members*)]
    (try
      ((requiring-resolve 'clojure.core.memoize/memo-clear!) @members-var)
      (catch Exception _)))
  :reset)

(defn- record-error! [ns-sym phase exception]
  (swap! errors conj {:ns ns-sym
                      :phase phase
                      :error exception
                      :message (.getMessage exception)
                      :timestamp (System/currentTimeMillis)}))

;; ============================================================================
;; S-Expression Counters (for raw forms)
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
;; AST Walking Utilities
;; ============================================================================

(defn ast-children
  "Get child nodes from an AST node via :children key."
  [node]
  (mapcat (fn [k]
            (let [v (get node k)]
              (if (sequential? v) v [v])))
          (:children node)))

(defn count-nodes
  "Count total expression nodes in AST."
  [ast]
  (count (ast/nodes ast)))

(defn max-if-depth
  "Find maximum nesting depth of :if nodes."
  [ast]
  (letfn [(depth [node current-depth]
            (let [is-if (= :if (:op node))
                  new-depth (if is-if (inc current-depth) current-depth)
                  children (ast-children node)
                  child-depths (map #(depth % new-depth) children)]
              (if (seq child-depths)
                (apply max new-depth child-depths)
                new-depth)))]
    (depth ast 0)))

(defn cyclomatic-complexity
  "Calculate cyclomatic complexity: count of decision points + 1.
   Decision points: :if :case :try :catch"
  [ast]
  (let [decision-ops #{:if :case :try :catch}
        nodes (ast/nodes ast)
        decisions (count (filter #(decision-ops (:op %)) nodes))]
    (inc decisions)))

(defn count-allocations
  "Count allocation nodes: :new :vector :map :set :with-meta (for literals)."
  [ast]
  (let [alloc-ops #{:new :vector :map :set}
        nodes (ast/nodes ast)]
    (count (filter #(alloc-ops (:op %)) nodes))))

;; ============================================================================
;; Hybrid Counters (raw forms aware)
;; ============================================================================

(defn- original-form
  "Get the original (pre-expansion) form from :raw-forms.
   For macro-expanded nodes, second element is the original macro invocation.
   For non-macro nodes, there's only one element."
  [raw-forms]
  (or (second raw-forms) (first raw-forms)))

(def ^:private macro-expanded-ops
  "Ops that represent actual macro-expanded code (not structural wrappers).
   Only these ops should use :raw-forms for raw metrics.

   Why these ops:
   - :invoke/:static-call/:instance-call - function calls, often from threading macros
   - :do - block expressions, common in let/when/cond expansions

   Excluded ops like :fn, :let, :local don't typically carry meaningful
   :raw-forms from macro expansion."
  #{:static-call :invoke :instance-call :do})

(defn- use-raw-forms?
  "Should we use :raw-forms for this node?"
  [ast]
  (and (seq (:raw-forms ast))
       (macro-expanded-ops (:op ast))))

(defn count-expressions
  "Count expressions. When raw?, treat macro-expanded nodes as single units."
  [ast raw?]
  (if (and raw? (use-raw-forms? ast))
    ;; For raw mode: count this macro expansion as 1 expression (don't recurse into expanded children)
    1
    (let [children (ast-children ast)]
      (reduce + 1 (map #(count-expressions % raw?) children)))))

(defn max-depth
  "Max nesting depth. When raw?, use :raw-forms where available."
  [ast raw?]
  (if (and raw? (use-raw-forms? ast))
    (sexp-max-depth (original-form (:raw-forms ast)))
    (let [children (ast-children ast)]
      (if (seq children)
        (inc (reduce max 0 (map #(max-depth % raw?) children)))
        0))))

;; ============================================================================
;; Per-definition Metrics
;; ============================================================================

(defn def-metrics
  "Extract all metrics for a single :def node.
   Returns map with :name :ns :file :line :metrics
   Metrics include both raw (pre-expansion) and expanded (post-expansion) variants."
  [def-node]
  (let [{:keys [name env init]} def-node
        {:keys [ns file line end-line]} env
        loc (if (and line end-line)
              (inc (- end-line line))
              1)
        ;; Raw form from top-level :raw-forms (contains unexpanded macros)
        raw-form (when (and init (seq (:raw-forms init)))
                   (original-form (:raw-forms init)))]
    {:name (str name)
     :ns (str ns)
     :file file
     :line line
     :metrics {:loc loc
               ;; Raw metrics (from source as written)
               :expressions-raw (if raw-form
                                  (count-sexp-forms raw-form)
                                  0)
               :max-depth-raw (if raw-form
                                (sexp-max-depth raw-form)
                                0)
               ;; Expanded metrics (from AST after macro expansion)
               :expressions-expanded (if init
                                       (count-expressions init false)
                                       0)
               :max-depth-expanded (if init
                                     (max-depth init false)
                                     0)}}))

;; ============================================================================
;; Unused Code Detection
;; ============================================================================

(defn extract-var-defs
  "Extract set of defined var symbols from AST nodes."
  [nodes]
  (into #{}
        (comp (filter #(= :def (:op %)))
              (map #(symbol (-> % :var .toSymbol))))
        nodes))

(defn extract-var-refs
  "Extract set of referenced var symbols from AST nodes."
  [nodes]
  (into #{}
        (comp (filter #(#{:var :the-var} (:op %)))
              (keep (fn [node]
                      (when-let [v (:var node)]
                        (symbol (.toSymbol v))))))
        nodes))

(defn find-unused-vars
  "Find vars that are defined but never referenced.
   Returns set of unused var symbols."
  [asts]
  (let [all-nodes (mapcat ast/nodes asts)
        defs (extract-var-defs all-nodes)
        refs (extract-var-refs all-nodes)]
    (set/difference defs refs)))

;; ============================================================================
;; Namespace Analysis
;; ============================================================================

(defn- skip-namespace?
  "Returns true for namespaces that break tools.analyzer.jvm when analyzed.
   These include the analyzer itself, namespaces it depends on."
  [ns-sym]
  (let [ns-str (str ns-sym)]
    (or (str/starts-with? ns-str "clojure.tools.analyzer")
        (str/starts-with? ns-str "clojure.spec")
        (str/starts-with? ns-str "clojure.core.memoize")
        (str/starts-with? ns-str "clojure.core.cache")
        (str/starts-with? ns-str "clojure.core.specs")
        (= ns-str "clojure.reflect"))))

(defn- ns-source-path
  "Get resource path for namespace source file."
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "." "/")
      (str/replace "-" "_")
      (str ".clj")))

(defn- read-all-forms
  "Read all forms from a reader."
  [rdr]
  (let [eof (Object.)]
    (loop [forms []]
      (let [form (read {:eof eof} rdr)]
        (if (identical? form eof)
          forms
          (recur (conj forms form)))))))

(defn- def-form?
  "Check if form is a def-like form."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{'def 'defn 'defn- 'defmacro 'defonce 'defmulti 'defmethod}
                  (first form))))

(defn- def-form-name
  "Extract name from a def form."
  [form]
  (when (def-form? form)
    (second form)))

(defn- form-metrics-from-sexp
  "Calculate metrics from s-expression (reader fallback)."
  [form ns-sym]
  (let [name (def-form-name form)
        body (drop 2 form)  ;; skip def name, get body
        body-form (if (string? (first body)) (rest body) body)  ;; skip docstring
        body-form (if (map? (first body-form)) (rest body-form) body-form)]  ;; skip attr-map
    {:name (str name)
     :ns (str ns-sym)
     :file nil
     :line nil
     :metrics {:loc (count (str/split-lines (pr-str form)))
               :expressions-raw (count-sexp-forms form)
               :expressions-expanded nil
               :max-depth-raw (sexp-max-depth form)
               :max-depth-expanded nil
               :failed? true}}))

(defn- analyze-form-with-fallback
  "Try to analyze a single form with ana.jvm/analyze, fall back to reader metrics."
  [form ns-sym env]
  (if-not (def-form? form)
    nil  ;; skip non-def forms
    (try
      (let [ast (ana.jvm/analyze form env)
            defs (filter #(= :def (:op %)) (ast/nodes ast))]
        (when (seq defs)
          (def-metrics (first defs))))
      (catch Throwable _
        (form-metrics-from-sexp form ns-sym)))))

(defn- analyze-ns-via-reader
  "Analyze namespace by reading source and analyzing each form individually."
  [ns-sym]
  (try
    (require ns-sym)
    (let [resource (clojure.java.io/resource (ns-source-path ns-sym))
          the-ns (find-ns ns-sym)]
      (if (and resource the-ns)
        (binding [*ns* the-ns]
          (let [env (ana.jvm/empty-env)]
            (with-open [rdr (java.io.PushbackReader. (clojure.java.io/reader resource))]
              (let [forms (read-all-forms rdr)]
                (->> forms
                     (keep #(analyze-form-with-fallback % ns-sym env))
                     vec)))))
        []))
    (catch Throwable e
      (record-error! ns-sym :analyze-via-reader e)
      [])))

(defn analyze-ns
  "Analyze a single namespace, returning seq of function data maps.
   Each map has :name :ns :file :line :metrics
   Tries analyze-ns first, then falls back to per-form analysis.
   Returns {:analyzed [...] :asts [...]} map."
  [ns-sym]
  (if (skip-namespace? ns-sym)
    {:analyzed (analyze-ns-via-reader ns-sym)
     :asts []}
    (try
      (require ns-sym)
      (let [asts (ana.jvm/analyze-ns ns-sym)]
        (if (seq asts)
          (let [defs (filter #(= :def (:op %)) asts)]
            {:analyzed (mapv def-metrics defs)
             :asts asts})
          ;; Empty result, try per-form analysis
          (do
            (record-error! ns-sym :analyze (ex-info "analyze-ns returned empty, trying per-form" {:ns ns-sym}))
            {:analyzed (analyze-ns-via-reader ns-sym)
             :asts []})))
      (catch Throwable e
        (record-error! ns-sym :analyze e)
        ;; Fall back to per-form analysis
        {:analyzed (analyze-ns-via-reader ns-sym)
         :asts []}))))

(defn analyze-nses
  "Analyze multiple namespaces.
   Returns seq of function data maps.
   Failed forms are included with :failed? true in metrics.
   Unused code detection is always performed, adding :unused? to metrics."
  [ns-syms]
  (let [results (mapv analyze-ns ns-syms)
        all-fn-data (vec (mapcat :analyzed results))
        all-asts (mapcat :asts results)
        unused-vars (find-unused-vars all-asts)
        unused-var-strs (into #{} (map str) unused-vars)]
    (mapv (fn [{:keys [ns name] :as fn-data}]
            (if (get-in fn-data [:metrics :failed?])
              fn-data  ;; don't mark failed as unused
              (let [full-name (str ns "/" name)
                    unused? (contains? unused-var-strs full-name)]
                (assoc-in fn-data [:metrics :unused?] unused?))))
          all-fn-data)))

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
