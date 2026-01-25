(ns clojure-compiler-treemap-view.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-compiler-treemap-view.core :as cctv]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Ensure fixture namespaces are loaded
(require 'clojure-compiler-treemap-view.fixtures.alpha
         'clojure-compiler-treemap-view.fixtures.alpha.utils
         'clojure-compiler-treemap-view.fixtures.alpha.handlers
         'clojure-compiler-treemap-view.fixtures.beta)

(deftest test-write-metrics
  (testing "write-metrics writes valid Prometheus format"
    (let [path (str (java.io.File/createTempFile "test-metrics" ".prom"))
          {:keys [result errors]} (cctv/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha])
          written-path (cctv/write-metrics result path)
          content (slurp written-path)
          lines (str/split-lines content)]
      (is (empty? errors))
      ;; Check header present
      (is (str/starts-with? (first lines) "# Clojure code metrics"))
      ;; Check compiler metrics present
      (is (some #(str/starts-with? % "clojure_expressions_raw{") lines))
      ;; Check classloader metrics present
      (is (some #(str/starts-with? % "clojure_bytecode_size{") lines)))))

(deftest test-viewer-html-exists
  (testing "pre-built viewer.html exists and is valid"
    (let [content (slurp (io/file "viewer.html"))]
      (is (str/includes? content "<!DOCTYPE html"))
      (is (str/includes? content "d3js.org"))  ; D3 is embedded inline
      (is (str/includes? content "treemap"))
      (is (str/includes? content "TREEMAP_DATA = null")))))
