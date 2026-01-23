(ns treemap.fixtures.gamma
  "Test fixtures for raw forms / threading macro analysis.")

(defn threading-simple [x]
  (-> x inc dec))

(defn threading-long [x]
  (-> x inc (* 2) (+ 10) dec str))

(defn no-macros [x]
  (str (dec (+ 10 (* 2 (inc x))))))
