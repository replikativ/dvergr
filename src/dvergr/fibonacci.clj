(ns dvergr.fibonacci
  "Fibonacci sequence utilities.")

(defn fibonacci
  "Returns the nth Fibonacci number.
   Uses iterative approach for efficiency.
   fibonacci(0) => 0, fibonacci(1) => 1, fibonacci(2) => 1, etc."
  [n]
  {:pre [(>= n 0) (integer? n)]}
  (if (< n 2)
    n
    (loop [a 0
           b 1
           i 1]
      (if (= i n)
        b
        (recur b (+ a b) (inc i))))))