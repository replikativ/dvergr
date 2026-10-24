(ns dvergr.benchmarks.company-cash
  "Generate reproducible company-cash room bundles. Use the artifact experiment
   runner for executable-model evaluation; ordinary room-run scores v1 answers."
  (:require [clojure.edn :as edn]
            [dvergr.benchmarks.company-cash.generate :as generate]))

(defn -main
  "clojure -M:benchmarks -m dvergr.benchmarks.company-cash OUT [OPTIONS-EDN]"
  [& args]
  (when-not (<= 1 (count args) 2)
    (throw (ex-info "Usage: company-cash OUT [OPTIONS-EDN]" {})))
  (prn (generate/write-bundle! (first args) (if-let [opts (second args)] (edn/read-string opts) {}))))
