(ns clj-compiler-view.fixtures.beta)

(defn allocating-fn
  "A function that allocates collections - tests allocation counting."
  [x]
  (let [v [1 2 3]
        m {:a 1 :b 2}
        s #{:x :y :z}]
    (into #{} (concat v (vals m) s [x]))))

(defn another-fn
  "A simple function."
  [x]
  (+ x 100))

(defn complex-fn
  "A function with multiple branches for complexity testing."
  [x y]
  (if (pos? x)
    (if (zero? y)
      (try
        (/ x y)
        (catch ArithmeticException _
          :div-by-zero))
      (* x y))
    (case y
      0 :zero
      1 :one
      :other)))
