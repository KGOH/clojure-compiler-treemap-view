(ns clojure-compiler-treemap-view.fixtures.alpha.handlers
  (:require [clojure-compiler-treemap-view.fixtures.alpha.utils :as utils]))

(defn process
  "Process with nested conditionals - tests if-depth metric."
  [x]
  (if (pos? x)
    (if (even? x)
      (if (> x 10)
        :big-even
        :small-even)
      :odd)
    :negative))

(defn handle
  "Chains multiple function calls."
  [x]
  (-> x process name utils/helper utils/format-output))
