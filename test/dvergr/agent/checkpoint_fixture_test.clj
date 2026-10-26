(ns dvergr.agent.checkpoint-fixture-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [datahike.gc-roots :as roots]
            [dvergr.agent.checkpoint-admission-test :as fixture]
            [dvergr.agent.checkpoint-prediction-test :as prediction]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.substrate.geschichte :as workspace]
            [geschichte.bytes :as bytes]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]
            [org.replikativ.spindel.world.scope :as scope]))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest fixture-construction-owns-only-its-fork-and-preserves-an-external-pin
  (@#'fixture/with-room-workspace
   (fn [room conn]
     (@#'fixture/commit! conn "C0")
     (let [selected (@#'prediction/checkpoint conn)
           branches (db/branches conn) root (random-uuid)]
       (try
         (let [result
               (evaluation/with-checkpoint-fixture!
                 room room nil selected
                 (fn []
                   (let [child (:conn (workspace/current-workspace))]
                     (repo/write! child "case.md" (bytes/utf8 "CASE-ONLY"))
                     (repo/stage-all! child)
                     (repo/commit! child {:message "Build selected case"})
                    ;; Test-owned root; production callers need a durable case
                    ;; intent before this IO. The fork scope must not own it.
                     (roots/pin! @child {:id root :ttl-ms nil :owner {:test :fixture}} {:sync? true})
                     {:commit (db/commit-id @child)
                      :observed (bytes/decode-utf8 (repo/read child "case.md"))})))]
           (is (= "CASE-ONLY" (:observed result)))
           (is (= branches (db/branches conn)))
           (is (false? (boolean (fs/exists? (workspace/filesystem) "/case.md"))))
           (is (contains? (roots/roots (:store @conn) {:sync? true}) root))
           (let [snapshot (db/commit-as-db (:store @conn) (:commit result))]
             (is (= "CASE-ONLY" (bytes/decode-utf8 (repo/read (atom snapshot) "case.md")))))
           (is (empty? (run/runs room)))
           (is (true? (evaluation/await-cleanups! room 3000))))
         (finally (roots/release! @conn root {:sync? true})))))))

(deftest fixture-failure-uses-the-existing-native-recovery-owner
  (@#'fixture/with-room-workspace
   (fn [room conn]
     (@#'fixture/commit! conn "C0")
     (let [selected (@#'prediction/checkpoint conn) branches (db/branches conn)
           cleanup-error (ex-info "Test cleanup refusal" {:private/owner (atom conn)})
           error (with-redefs [scope/discard! (fn [_] (fn [_ reject] (reject cleanup-error)))]
                   (failure #(evaluation/with-checkpoint-fixture!
                               room room nil selected (fn [] (atom :not-portable)))))
           token (:cleanup/token (ex-data error))]
       (try
         (is (= ::evaluation/checkpoint-fixture-cleanup-required (:type (ex-data error))))
         (is (uuid? token))
         (is (roster/data-value? (ex-data error)))
         (is (nil? (ex-cause error)))
         (is (= (inc (count branches)) (count (db/branches conn))))
         (is (true? (evaluation/recover-native-cleanup! room token)))
         (is (= branches (db/branches conn)))
         (is (true? (evaluation/await-cleanups! room 3000)))
         (finally
           (when (and token (not= branches (db/branches conn)))
             (evaluation/recover-native-cleanup! room token))))))))
