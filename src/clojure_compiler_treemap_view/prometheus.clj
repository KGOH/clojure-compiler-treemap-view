(ns clojure-compiler-treemap-view.prometheus
  "Prometheus format export for metrics data."
  (:require [clojure.string :as str]))

(defn- escape-label-value
  "Escape a string for use as a Prometheus label value.
   Per spec: backslash, double-quote, and newline must be escaped."
  [s]
  (when s
    (-> (str s)
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n"))))

(defn- format-labels
  "Format a map of labels as Prometheus label string. Skips nil values."
  [labels]
  (let [pairs (->> labels
                   (filter (fn [[_ v]] (some? v)))
                   (map (fn [[k v]] (str (name k) "=\"" (escape-label-value v) "\""))))]
    (when (seq pairs)
      (str "{" (str/join "," pairs) "}"))))

(defn- metric-line [metric-name labels value]
  (str metric-name (format-labels labels) " " value))

(defn- compiler-entry->lines [{:keys [name ns line metrics]}]
  (let [labels {:ns ns :name name :line (when line (str line))}
        {:keys [expressions-raw expressions-expanded
                max-depth-raw max-depth-expanded unused?]} metrics]
    [(metric-line "clojure_expressions_raw" labels expressions-raw)
     (metric-line "clojure_expressions_expanded" labels expressions-expanded)
     (metric-line "clojure_max_depth_raw" labels max-depth-raw)
     (metric-line "clojure_max_depth_expanded" labels max-depth-expanded)
     (metric-line "clojure_unused" labels (if unused? 1 0))]))

(defn- classloader-entry->lines [{:keys [name ns full-name metrics]}]
  (let [labels {:ns ns :name name :full_name full-name}]
    [(metric-line "clojure_bytecode_size" labels (:bytecode-size metrics))]))

(defn format-prometheus
  "Format metrics data as Prometheus exposition format string."
  [metrics-data]
  (let [header ["# Clojure code metrics - Prometheus format"]
        compiler-header ["# HELP clojure_expressions_raw S-expression count before macro expansion"
                         "# TYPE clojure_expressions_raw gauge"
                         "# HELP clojure_expressions_expanded S-expression count after macro expansion"
                         "# TYPE clojure_expressions_expanded gauge"
                         "# HELP clojure_max_depth_raw Max nesting depth before macro expansion"
                         "# TYPE clojure_max_depth_raw gauge"
                         "# HELP clojure_max_depth_expanded Max nesting depth after macro expansion"
                         "# TYPE clojure_max_depth_expanded gauge"
                         "# HELP clojure_unused 1 if var defined but never referenced"
                         "# TYPE clojure_unused gauge"]
        compiler-lines (mapcat compiler-entry->lines (:compiler metrics-data))
        classloader-header ["# HELP clojure_bytecode_size Class bytecode size in bytes"
                            "# TYPE clojure_bytecode_size gauge"]
        classloader-lines (mapcat classloader-entry->lines (:classloader metrics-data))
        all-lines (concat header [""] compiler-header compiler-lines [""] classloader-header classloader-lines)]
    (str/join "\n" all-lines)))

(defn write-prometheus
  "Write metrics data in Prometheus format. Returns absolute path written."
  [metrics-data path]
  (let [file (.getAbsolutePath (java.io.File. ^String path))]
    (spit file (format-prometheus metrics-data))
    file))
