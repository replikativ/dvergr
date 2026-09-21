(ns dvergr.test-support
  "Shared by tests."
  (:require [clojure.test :as t]))

(defn skip!
  "Report the running test as pending. A test that needs a checkout the machine
   does not have (CI) must say so: kaocha fails a test that ran without
   assertions."
  [message]
  (println "SKIP" message)
  (t/do-report {:type :kaocha/pending}))
