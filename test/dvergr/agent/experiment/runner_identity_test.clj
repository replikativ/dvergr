(ns dvergr.agent.experiment.runner-identity-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.experiment.runner :as runner]))

(deftest the-libraries-are-part-of-an-experiment-s-identity
  ;; a resume after a library upgrade folded Attempts of two versions into
  ;; one Scorecard (results-correctness review): their versions are in it now
  (let [v (runner/library-versions)]
    (is (string? (get v "org.replikativ/spindel")) (pr-str v))
    (is (string? (get v "org.replikativ/datahike")) (pr-str v))))
