(ns clojure-compiler-treemap-view.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-compiler-treemap-view.core :as core]
            [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [clojure.string :as str]))

;; Ensure fixture namespaces are loaded
(require 'clojure-compiler-treemap-view.fixtures.alpha
         'clojure-compiler-treemap-view.fixtures.alpha.utils
         'clojure-compiler-treemap-view.fixtures.alpha.handlers
         'clojure-compiler-treemap-view.fixtures.beta)

(deftest test-build-hierarchy
  (testing "builds correct tree structure"
    (let [fn-data [{:name "fn1" :ns "foo.bar" :metrics {:loc 10}}
                   {:name "fn2" :ns "foo.bar" :metrics {:loc 20}}
                   {:name "fn3" :ns "foo.baz" :metrics {:loc 15}}
                   {:name "fn4" :ns "qux" :metrics {:loc 5}}]
          tree (cctv.analyze/build-hierarchy fn-data)]
      ;; Root has children
      (is (= "root" (:name tree)))
      (is (seq (:children tree)))

      ;; Check foo subtree
      (let [foo (first (filter #(= "foo" (:name %)) (:children tree)))]
        (is foo "should have foo namespace")
        (is (seq (:children foo)))

        ;; Check bar subtree under foo
        (let [bar (first (filter #(= "bar" (:name %)) (:children foo)))]
          (is bar "should have bar under foo")
          (is (= 2 (count (:children bar))) "bar should have 2 functions")))

      ;; Check qux is at root level
      (let [qux (first (filter #(= "qux" (:name %)) (:children tree)))]
        (is qux "should have qux at root")
        (is (= 1 (count (:children qux))) "qux should have 1 function")))))

(deftest test-hierarchy-with-fixtures
  (testing "groups fixture namespaces correctly"
    (let [{:keys [result]} (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha
                                                   clojure-compiler-treemap-view.fixtures.alpha.utils
                                                   clojure-compiler-treemap-view.fixtures.alpha.handlers
                                                   clojure-compiler-treemap-view.fixtures.beta])
          tree (cctv.analyze/build-hierarchy result)
          ccv-node (first (filter #(= "clojure-compiler-treemap-view" (:name %)) (:children tree)))]
      (is ccv-node "should have clojure-compiler-treemap-view top-level")

      (let [fixtures (first (filter #(= "fixtures" (:name %)) (:children ccv-node)))]
        (is fixtures "should have fixtures under clojure-compiler-treemap-view")

        ;; Check alpha and beta are siblings under fixtures
        (let [alpha (first (filter #(= "alpha" (:name %)) (:children fixtures)))
              beta (first (filter #(= "beta" (:name %)) (:children fixtures)))]
          (is alpha "should have alpha")
          (is beta "should have beta")

          ;; Alpha should have utils and handlers as children (nested namespaces)
          (let [utils (first (filter #(= "utils" (:name %)) (:children alpha)))
                handlers (first (filter #(= "handlers" (:name %)) (:children alpha)))]
            (is utils "should have utils under alpha")
            (is handlers "should have handlers under alpha")))))))

(deftest test-render-html-generates-valid-output
  (testing "render-html generates valid HTML"
    (let [{:keys [result]} (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha])
          tree (cctv.analyze/build-hierarchy result)
          content (core/render-html tree)]
      (is (str/includes? content "<!DOCTYPE html"))
      (is (str/includes? content "d3.v7.min.js"))
      (is (str/includes? content "treemap"))
      (is (str/includes? content "const defaultSize = 'expressions-raw'"))
      (is (str/includes? content "const defaultColor = 'max-depth-raw'")))))

(deftest test-render-html-with-options
  (testing "render-html respects options"
    (let [{:keys [result]} (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha])
          tree (cctv.analyze/build-hierarchy result)
          content (core/render-html tree :size :expressions-expanded :color :max-depth-expanded)]
      (is (str/includes? content "const defaultSize = 'expressions-expanded'"))
      (is (str/includes? content "const defaultColor = 'max-depth-expanded'")))))

(deftest test-treemap-validates-metric-keys
  (testing "treemap! throws helpful error for invalid size metric"
    (let [ex (try
               (core/treemap! '[clojure-compiler-treemap-view.fixtures.alpha] :size :typo)
               nil
               (catch Exception e e))]
      (is (some? ex) "should throw for invalid metric")
      (is (str/includes? (ex-message ex) "Invalid :size metric"))
      (is (str/includes? (ex-message ex) ":typo"))
      (is (str/includes? (ex-message ex) ":expressions-raw"))))
  (testing "treemap! throws helpful error for invalid color metric"
    (let [ex (try
               (core/treemap! '[clojure-compiler-treemap-view.fixtures.alpha] :color :bad-key)
               nil
               (catch Exception e e))]
      (is (some? ex) "should throw for invalid metric")
      (is (str/includes? (ex-message ex) "Invalid :color metric"))
      (is (str/includes? (ex-message ex) ":bad-key")))))
