(ns math-lib)

(defn square
  "Returns the square of a number"
  [x]
  (* x x))

(defn cube
  "Returns the cube of a number"
  [y]
  (* y y y))

(defn sum-powers
  "Computes x² + y³ by calling square and cube functions"
  [x y]
  (+ (square x) (cube y)))