(ns sample.core
  (:require [clojure.string :as str]))

(defn greet
  "Greet a person by name."
  [name]
  (str "Hello, " name "!"))

(defn farewell
  "Say goodbye to a person."
  [name]
  (str "Goodbye, " name "."))

(defn process-names
  "Process a list of names."
  [names]
  (mapv greet names))

(defn calculate
  "Do some calculation."
  [x y]
  (+ x y))
