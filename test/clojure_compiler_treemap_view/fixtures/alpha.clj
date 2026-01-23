(ns clojure-compiler-treemap-view.fixtures.alpha)

(defn simple-fn
  "A simple function for testing basic metrics."
  [x]
  (inc x))

(defn unused-fn
  "This function is never called - dead code."
  []
  :dead)

(defn multi-arity
  "A multi-arity function."
  ([x] (multi-arity x 1))
  ([x y] (+ x y)))
