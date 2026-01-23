(ns clj-compiler-view.fixtures.alpha.utils)

(defn helper
  "Helper function for testing."
  [x]
  (* x 2))

(defn format-output
  "Formats output as string."
  [x]
  (str "Result: " x))
