(ns dvergr.agent.run.family-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.run.family :as family]))

(deftest sealed-family-retains-its-existing-preparation-and-single-use-admission
  (let [token (random-uuid) child (random-uuid)
        reserved (family/reserve (family/empty-family) token)
        closed (family/seal reserved)
        preparing (-> closed (family/claim token child) (family/preparation-entered token))
        started (family/starting preparing token child)
        admitted (family/admitted started token child)]
    (is (not (family/quiescent? closed)))
    (is (thrown? Exception (family/reserve closed (random-uuid))))
    (is (thrown? Exception (family/claim preparing token (random-uuid))))
    (is (not (family/releasable? (get-in (family/callback-exited preparing token) [:leases token]))))
    (is (not (family/releasable? (get-in (family/callback-exited started token) [:leases token]))))
    (is (thrown? Exception (family/starting admitted token child)))
    (is (thrown? Exception (family/child-returned admitted token (random-uuid))))
    (let [returned (family/child-returned admitted token child)]
      (is (not (family/releasable? (get-in returned [:leases token]))))
      (let [done (family/callback-exited returned token)]
        (is (family/releasable? (get-in done [:leases token])))
        (is (family/quiescent? (-> done (family/releasing token) (family/released token))))))))

(deftest publication-and-cancellation-do-not-change-the-owned-child
  (let [token (random-uuid) child (random-uuid)
        original (-> (family/empty-family) (family/reserve token)
                     (family/claim token child) (family/starting token child)
                     (family/admitted token child) (family/require-publication token))
        cancelled (family/cancel original)
        done (-> cancelled (family/child-returned token child) (family/callback-exited token))]
    (is (= child (get-in done [:leases token :child-id])))
    (is (true? (get-in done [:leases token :cancelled?])))
    (is (not (family/releasable? (get-in done [:leases token]))))
    (is (family/releasable? (get-in (family/publication-acknowledged done token) [:leases token])))
    (is (thrown? Exception (family/require-publication done token)))))

(deftest acknowledged-preparation-cleanup-can-release-without-a-child-run
  (let [token (random-uuid)
        held (-> (family/empty-family) (family/reserve token) (family/claim token (random-uuid))
                 (family/preparation-entered token) (family/callback-exited token) family/seal)]
    (is (not (family/releasable? (get-in held [:leases token]))))
    (is (thrown? Exception (family/releasing held token)))
    (let [cleaned (family/preparation-cleaned held token)]
      (is (family/quiescent? (-> cleaned (family/releasing token) (family/released token)))))))
