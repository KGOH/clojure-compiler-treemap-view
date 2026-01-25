(ns clojure-compiler-treemap-view.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-compiler-treemap-view.core :as cctv]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [jsonista.core :as json]))

;; Ensure fixture namespaces are loaded
(require 'clojure-compiler-treemap-view.fixtures.alpha
         'clojure-compiler-treemap-view.fixtures.alpha.utils
         'clojure-compiler-treemap-view.fixtures.alpha.handlers
         'clojure-compiler-treemap-view.fixtures.beta)

(deftest test-write-metrics
  (testing "write-metrics writes valid JSON"
    (let [path (str (java.io.File/createTempFile "test-metrics" ".json"))
          {:keys [result errors]} (cctv/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha])
          written-path (cctv/write-metrics result path)
          content (slurp written-path)
          data (json/read-value content)]
      (is (empty? errors))
      (is (= 1 (get data "version")))
      (is (get data "generated"))
      (is (seq (get data "compiler")))
      (is (seq (get data "classloader"))))))

(deftest test-viewer-html-exists
  (testing "pre-built viewer.html exists and is valid"
    (let [content (slurp (io/file "viewer.html"))]
      (is (str/includes? content "<!DOCTYPE html"))
      (is (str/includes? content "d3js.org"))  ; D3 is embedded inline
      (is (str/includes? content "treemap"))
      (is (str/includes? content "TREEMAP_DATA = null")))))
