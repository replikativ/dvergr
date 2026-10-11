(ns dvergr.substrate.log-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.substrate.log :as log]
            [taoensso.telemere :as tel])
  (:import (java.util.logging Level LogRecord)))

(deftest java-util-logging-reaches-telemere
  ;; `to-file!` removes JUL's console handlers; its records (Lucene's) must
  ;; still reach the log file, through Telemere
  (let [h (#'log/jul->telemere)
        signal (tel/with-signal (.publish h (doto (LogRecord. Level/WARNING "disk {0} is slow")
                                              (.setParameters (object-array ["/x"]))
                                              (.setLoggerName "org.apache.lucene"))))]
    (is (= :warn (:level signal)))
    (is (= "disk /x is slow" (force (:msg_ signal))))
    (is (= {:logger "org.apache.lucene"} (:data signal)))
    (is (nil? (tel/with-signal (.publish h (LogRecord. Level/FINE "noise")))) "below INFO is dropped")
    (let [e (ex-info "index corrupt" {})
          signal (tel/with-signal (.publish h (doto (LogRecord. Level/SEVERE "merge failed") (.setThrown e))))]
      (is (= :error (:level signal)))
      (is (identical? e (:error signal)) "the exception comes along"))))
