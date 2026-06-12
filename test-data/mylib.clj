(ns mylib)

(defn add [a b]
  "Adds two numbers together"
  (+ a b))

(defn multiply [a b]
  "Multiplies two numbers together"
  (* a b))

(defn calculate [x y z]
  "Performs addition and multiplication: (x + y) * z"
  (multiply (add x y) z))