(ns clojure-compiler-treemap-view.prometheus-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-compiler-treemap-view.prometheus :as prom]
            [clojure.string :as str]))

(deftest test-write-prometheus-basic
  (testing "writes valid prometheus format"
    (let [path (str (java.io.File/createTempFile "test-metrics" ".prom"))
          metrics-data {:compiler [{:name "my-fn"
                                    :ns "my.namespace"
                                    :line 42
                                    :metrics {:expressions-raw 12
                                              :expressions-expanded 28
                                              :max-depth-raw 2
                                              :max-depth-expanded 4
                                              :unused? false}}]
                        :classloader [{:name "my_fn"
                                       :ns "my.namespace"
                                       :full-name "my.namespace$my_fn"
                                       :metrics {:bytecode-size 1234}}]}
          written-path (prom/write-prometheus metrics-data path)
          content (slurp written-path)
          lines (str/split-lines content)]
      ;; Check header
      (is (str/starts-with? (first lines) "# Clojure code metrics"))
      ;; Check compiler metrics present
      (is (some #(str/starts-with? % "clojure_expressions_raw{") lines))
      (is (some #(str/starts-with? % "clojure_expressions_expanded{") lines))
      (is (some #(str/starts-with? % "clojure_max_depth_raw{") lines))
      (is (some #(str/starts-with? % "clojure_max_depth_expanded{") lines))
      (is (some #(str/starts-with? % "clojure_unused{") lines))
      ;; Check classloader metrics present
      (is (some #(str/starts-with? % "clojure_bytecode_size{") lines))
      ;; Check specific values
      (is (some #(and (str/includes? % "ns=\"my.namespace\"")
                      (str/includes? % "name=\"my-fn\"")
                      (str/ends-with? % " 12")) lines))
      (is (some #(str/ends-with? % " 1234") lines)))))

(deftest test-escape-label-values
  (testing "escapes special characters in label values"
    (let [path (str (java.io.File/createTempFile "test-escape" ".prom"))
          metrics-data {:compiler [{:name "fn-with\"quote"
                                    :ns "ns.with\\backslash"
                                    :line 1
                                    :metrics {:expressions-raw 1
                                              :expressions-expanded 1
                                              :max-depth-raw 1
                                              :max-depth-expanded 1
                                              :unused? false}}]
                        :classloader []}
          written-path (prom/write-prometheus metrics-data path)
          content (slurp written-path)]
      ;; Backslash should be escaped as \\
      (is (str/includes? content "ns.with\\\\backslash"))
      ;; Quote should be escaped as \"
      (is (str/includes? content "fn-with\\\"quote")))))

(deftest test-nil-label-values
  (testing "handles nil label values gracefully"
    (let [path (str (java.io.File/createTempFile "test-nil" ".prom"))
          metrics-data {:compiler [{:name "orphan-fn"
                                    :ns nil
                                    :line nil
                                    :metrics {:expressions-raw 5
                                              :expressions-expanded 5
                                              :max-depth-raw 1
                                              :max-depth-expanded 1
                                              :unused? true}}]
                        :classloader [{:name "SomeClass"
                                       :ns nil
                                       :full-name "SomeClass"
                                       :metrics {:bytecode-size 500}}]}
          written-path (prom/write-prometheus metrics-data path)
          content (slurp written-path)]
      ;; Should not contain ns="" or line="" for nil values
      (is (not (str/includes? content "ns=\"\"")))
      ;; Should still have name label
      (is (str/includes? content "name=\"orphan-fn\""))
      ;; unused? true should be 1
      (is (some #(and (str/includes? % "clojure_unused")
                      (str/ends-with? % " 1"))
                (str/split-lines content))))))

(deftest test-empty-metrics-data
  (testing "handles empty metrics data"
    (let [path (str (java.io.File/createTempFile "test-empty" ".prom"))
          metrics-data {:compiler [] :classloader []}
          written-path (prom/write-prometheus metrics-data path)
          content (slurp written-path)
          lines (str/split-lines content)]
      ;; Should still have headers
      (is (some #(str/starts-with? % "# HELP clojure_expressions_raw") lines))
      (is (some #(str/starts-with? % "# TYPE clojure_expressions_raw") lines))
      ;; Should not have any metric lines (non-comment, non-empty)
      (is (not (some #(and (not (str/starts-with? % "#"))
                           (not (str/blank? %))
                           (str/includes? % "{"))
                     lines))))))
