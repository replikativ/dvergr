(ns sample.core
  "Sample namespace for testing code indexing.")

(defn greet
  "Greet a person by name."
  [name]
  (str "Hello, " name "!"))

(defn farewell
  "Say goodbye to a person."
  [name]
  (str "Goodbye, " name "!"))

(defn process-greeting
  "Process a greeting for multiple names."
  [names]
  (mapv greet names))

(defn calculate
  "Calculate something."
  [x y]
  (+ x y (count (farewell "test"))))
