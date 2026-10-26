(ns dvergr.laws.scaffold-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api]
            [dvergr.core]
            [geschichte.repo]
            [kontor.core]
            [kontor.resource]
            [org.replikativ.spindel.core]
            [sci.core]
            [yggdrasil.protocols]))

(deftest core-dependencies-load
  (doseq [namespace '[dvergr.core
                      org.replikativ.spindel.core
                      sci.core
                      datahike.api
                      yggdrasil.protocols
                      kontor.core
                      kontor.resource
                      geschichte.repo]]
    (is (some? (find-ns namespace)) (str namespace " loads"))))
