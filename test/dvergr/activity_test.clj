(ns dvergr.activity-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.activity :as activity]))

(deftest tool-activity-identity-is-canonical-and-stable
  (testing "the same typed source identity hashes to the same activity UUID"
    (let [run-id (random-uuid)
          source-id (random-uuid)
          uses [{:tool-use/id "call-1" :tool-use/name "clojure_eval"}]
          first-id (:activity/id (first (activity/tool-activities
                                         run-id source-id uses)))
          replay-id (:activity/id (first (activity/tool-activities
                                          run-id source-id uses)))
          other-id (:activity/id (first (activity/tool-activities
                                         run-id source-id
                                         [{:tool-use/id "call-2"
                                           :tool-use/name "clojure_eval"}])))]
      (is (uuid? first-id))
      (is (= first-id replay-id))
      (is (not= first-id other-id)))))
