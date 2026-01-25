(ns clojure-compiler-treemap-view.analyze-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-compiler-treemap-view.analyze :as cctv.analyze]
            [clojure-compiler-treemap-view.agent :as agent]))

(deftest test-ns->path
  (testing "splits namespace string into segments"
    (is (= ["foo" "bar" "baz"] (cctv.analyze/ns->path "foo.bar.baz")))
    (is (= ["single"] (cctv.analyze/ns->path "single")))
    (is (= ["clojure-compiler-treemap-view" "fixtures" "alpha"] (cctv.analyze/ns->path "clojure-compiler-treemap-view.fixtures.alpha")))))

;; ============================================================================
;; S-Expression Counter Tests
;; ============================================================================

(deftest test-count-sexp-forms
  (testing "counts atoms as 1"
    (is (= 1 (cctv.analyze/count-sexp-forms 'x)))
    (is (= 1 (cctv.analyze/count-sexp-forms 42)))
    (is (= 1 (cctv.analyze/count-sexp-forms "string"))))
  (testing "counts lists (1 for list + recursive count of elements)"
    (is (= 3 (cctv.analyze/count-sexp-forms '(inc x))))        ; 1 + inc(1) + x(1)
    (is (= 5 (cctv.analyze/count-sexp-forms '(-> x inc dec)))) ; 1 + ->(1) + x(1) + inc(1) + dec(1)
    (is (= 5 (cctv.analyze/count-sexp-forms '(dec (inc x)))))) ; 1 + dec(1) + (1 + inc(1) + x(1))
  (testing "counts vectors"
    (is (= 4 (cctv.analyze/count-sexp-forms '[1 2 3]))))       ; 1 + 1 + 1 + 1
  (testing "counts maps"
    (is (= 5 (cctv.analyze/count-sexp-forms '{:a 1 :b 2})))))

(deftest test-sexp-max-depth
  (testing "atoms have depth 0"
    (is (= 0 (cctv.analyze/sexp-max-depth 'x)))
    (is (= 0 (cctv.analyze/sexp-max-depth 42))))
  (testing "flat list has depth 1"
    (is (= 1 (cctv.analyze/sexp-max-depth '(-> x inc dec)))))
  (testing "nested list increases depth"
    (is (= 2 (cctv.analyze/sexp-max-depth '(dec (inc x)))))
    (is (= 3 (cctv.analyze/sexp-max-depth '(a (b (c x))))))))

;; ============================================================================
;; Namespace Analysis Tests (via compiler hooks)
;; ============================================================================

(deftest test-analyze-ns
  (testing "returns function data for namespace with all metrics"
    (let [{:keys [result errors]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.alpha)]
      (is (= [] errors) "should have no errors")
      (is (>= (count result) 3))
      (is (every? :name result))
      (is (every? :ns result))
      (is (every? :metrics result))
      (is (every? #(get-in % [:metrics :expressions-raw]) result))
      (is (every? #(get-in % [:metrics :expressions-expanded]) result))
      (is (every? #(get-in % [:metrics :max-depth-raw]) result))
      (is (every? #(get-in % [:metrics :max-depth-expanded]) result)))))

(deftest test-raw-vs-expanded-metrics
  (testing "raw metrics are less than expanded for threading macros"
    (let [{:keys [result]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.gamma)
          threading (first (filter #(= "threading-simple" (:name %)) result))]
      ;; Raw depth should be less (threading is flat in source)
      (is (< (get-in threading [:metrics :max-depth-raw])
             (get-in threading [:metrics :max-depth-expanded]))))))

(deftest test-find-unused-vars-via-hooks
  (testing "identifies unused functions via compiler hooks"
    (agent/clear!)
    (agent/clear-var-references!)
    (doseq [ns-sym '[clojure-compiler-treemap-view.fixtures.alpha
                     clojure-compiler-treemap-view.fixtures.alpha.utils
                     clojure-compiler-treemap-view.fixtures.alpha.handlers
                     clojure-compiler-treemap-view.fixtures.beta]]
      (require ns-sym :reload))
    (let [unused-vars (agent/find-unused-vars '[clojure-compiler-treemap-view.fixtures.alpha
                                                clojure-compiler-treemap-view.fixtures.alpha.utils
                                                clojure-compiler-treemap-view.fixtures.alpha.handlers
                                                clojure-compiler-treemap-view.fixtures.beta])]
      ;; unused-fn in alpha is never referenced
      (is (contains? unused-vars "clojure-compiler-treemap-view.fixtures.alpha/unused-fn"))
      ;; helper and format-output ARE referenced in handlers
      (is (not (contains? unused-vars "clojure-compiler-treemap-view.fixtures.alpha.utils/helper")))
      (is (not (contains? unused-vars "clojure-compiler-treemap-view.fixtures.alpha.utils/format-output"))))))

(deftest test-analyze-nses
  (testing "adds unused? flag to metrics"
    (let [{:keys [result errors]} (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha
                                                          clojure-compiler-treemap-view.fixtures.alpha.utils
                                                          clojure-compiler-treemap-view.fixtures.alpha.handlers
                                                          clojure-compiler-treemap-view.fixtures.beta])
          compiler-result (:compiler result)
          by-name (group-by :name compiler-result)
          unused (first (get by-name "unused-fn"))
          helper (first (get by-name "helper"))]
      (is (= [] errors) "should have no errors")
      (is (get-in unused [:metrics :unused?]) "unused-fn should be marked unused")
      (is (not (get-in helper [:metrics :unused?])) "helper should not be marked unused"))))

(deftest test-analyze-ns-metrics-format
  (testing "extracts all metrics in expected format"
    (let [{:keys [result]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.alpha)
          simple (first (filter #(= "simple-fn" (:name %)) result))]
      (is simple "simple-fn should be in analyzed results")
      (is (= "simple-fn" (:name simple)))
      (is (= "clojure-compiler-treemap-view.fixtures.alpha" (:ns simple)))
      (is (number? (get-in simple [:metrics :expressions-raw])))
      (is (number? (get-in simple [:metrics :expressions-expanded])))
      (is (number? (get-in simple [:metrics :max-depth-raw])))
      (is (number? (get-in simple [:metrics :max-depth-expanded]))))))

(deftest test-analyze-multiple-namespaces
  (testing "can analyze multiple namespaces in one call"
    ;; This tests that analyze-ns properly filters to only the target namespace
    (let [{:keys [result]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.beta)]
      (is (seq result) "should have some analyzed forms")
      (doseq [fn-data result]
        (is (= "clojure-compiler-treemap-view.fixtures.beta" (:ns fn-data))
            (str "Expected ns clojure-compiler-treemap-view.fixtures.beta but got " (:ns fn-data)
                 " for " (:name fn-data)))))))

;; ============================================================================
;; Edge Case Tests
;; ============================================================================

(deftest test-analyze-empty-namespace
  (testing "empty namespace returns empty results"
    (let [{:keys [result errors]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.empty)]
      (is (= [] result))
      (is (= [] errors)))))

(deftest test-analyze-broken-namespace
  (testing "broken namespace returns empty results with error"
    (let [{:keys [result errors]} (cctv.analyze/analyze-ns 'clojure-compiler-treemap-view.fixtures.broken)]
      (is (= [] result))
      (is (= 1 (count errors)))
      (is (= 'clojure-compiler-treemap-view.fixtures.broken (:ns (first errors)))))))

(deftest test-build-hierarchy-empty
  (testing "build-hierarchy handles empty input"
    (is (= {:name "root" :children []} (cctv.analyze/build-hierarchy [])))))

(deftest test-analyze-nses-with-broken
  (testing "analyze-nses continues when one namespace fails"
    (let [{:keys [result errors]} (cctv.analyze/analyze-nses '[clojure-compiler-treemap-view.fixtures.alpha
                                                          clojure-compiler-treemap-view.fixtures.broken])]
      (is (seq (:compiler result)) "should have results from working namespace")
      (is (some #(= 'clojure-compiler-treemap-view.fixtures.broken (:ns %)) errors)))))
