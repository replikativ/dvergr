(ns dvergr.rooms.publication-basis-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.blob :as blob]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.publication :as publication]
            [dvergr.rooms.validated-basis-test :as fixture]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.query :as query]
            [geschichte.repo :as repo]))

(defn- with-repository [f] (@#'fixture/with-repository true f))
(defn- options [conn journal]
  (let [plan (repo/prepare-commit conn {:message "Identify target"})]
    {:identity {:publication/id (random-uuid) :publication/intent (random-uuid)
                :publication/actor (random-uuid) :publication/manifest (random-uuid)
                :publication/repository (:repository plan)
                :publication/store (get-in plan [:workspace :store :id])
                :publication/backend (get-in plan [:workspace :store :backend])
                :publication/branch (get-in plan [:workspace :branch])
                :publication/ref (:ref plan)}
     :authorize! (constantly true)
     :before-dispatch! #(swap! journal conj %)}))

(deftest actual-batch-validates-complete-after-snapshot-before-applying
  (with-repository
    (fn [room system conn]
      (let [journal (atom []) seen (atom [])
            before (get (repo/worktree conn) "other.md")
            prepared (assoc (options conn journal)
                            :validate-basis!
                            (fn [db]
                              (swap! seen conj db)
                              (let [tree (query/worktree db)]
                                (and (= before (get tree "other.md"))
                                     (= (blob/blob-id (.getBytes "updated" "UTF-8"))
                                        (get-in tree ["page.md" :content]))
                                     (= (blob/blob-id (.getBytes "new" "UTF-8"))
                                        (get-in tree ["wiki/new.md" :content]))))))
            result (files/write-to-completion! room system
                                               {"page.md" {:text "updated"} "wiki/new.md" {:text "new"}}
                                               {:publication prepared
                                                :expected-worktree {"/page.md" (get (repo/worktree conn) "page.md")
                                                                    "/wiki/new.md" nil}})]
        (is (= :returned (:completion result)))
        (is (true? (get-in result [:result :applied?])))
        (is (nil? (:error result)))
        (is (= 1 (count @seen)))
        (is (= 1 (count @journal)))
        (is (some? (publication/recover @conn (:identity prepared))))
        (is (contains? files/write-capabilities :validated-publication-basis))))))

(deftest refusing-or-malformed-validators-never-enter-applying
  (with-repository
    (fn [room system conn]
      (doseq [validator [(constantly false) (constantly nil) (constantly (promise))
                         (fn [_] (throw (ex-info "Invalid semantic output" {})))
                         nil :not-host-code]]
        (let [journal (atom []) before (:geschichte.commit/id (repo/head-commit conn))
              prepared (assoc (options conn journal) :validate-basis! validator)
              result (files/write-to-completion! room system {"page.md" {:text "candidate"}}
                                                 {:publication prepared})]
          (is (some? (:error result)))
          (is (not= :returned (:completion result)))
          (is (empty? @journal))
          (is (= before (:geschichte.commit/id (repo/head-commit conn))))
          (is (nil? (publication/recover @conn (:identity prepared)))))))))

(deftest direct-commit-helper-rejects-malformed-validators-before-staging
  (with-repository
    (fn [room system conn]
      (let [workspace (gs/workspace-for-system! system)
            staged (atom 0) before (repo/worktree conn)]
        (doseq [invalid [nil false :not-host-code]]
          (with-redefs [repo/stage! (fn [& _] (swap! staged inc) (throw (AssertionError. "Must refuse before staging")))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"explicit publication validator"
                 (forks/commit-paths-in-workspace!
                  room workspace {"/page.md" (str (get-in before ["page.md" :content]))}
                  "Refuse malformed guard"
                  {:publication (assoc (options conn (atom [])) :validate-basis! invalid)})))))
        (is (zero? @staged))
        (is (= before (repo/worktree conn)))))))

(deftest untouched-source-race-before-capture-is-visible-to-fixed-host-validation
  (with-repository
    (fn [room system conn]
      (let [journal (atom []) original forks/commit-paths-in-workspace!
            unchanged (get (repo/worktree conn) "other.md")
            prepared (assoc (options conn journal) :validate-basis!
                            #(= unchanged (get (query/worktree %) "other.md")))
            before (:geschichte.commit/id (repo/head-commit conn))
            result (with-redefs [forks/commit-paths-in-workspace!
                                 (fn [& args]
                                   (repo/write! conn "other.md" (.getBytes "concurrent source" "UTF-8"))
                                   (apply original args))]
                     (files/write-to-completion! room system {"page.md" {:text "candidate"}}
                                                 {:publication prepared}))]
        (is (some? (:error result)))
        (is (empty? @journal))
        (is (= before (:geschichte.commit/id (repo/head-commit conn))))
        (is (nil? (publication/recover @conn (:identity prepared))))))))

(deftest mutation-after-validation-refuses-publication-even-for-noop
  (with-repository
    (fn [room system conn]
      (doseq [text ["original" "new candidate"]]
        (let [journal (atom []) calls (atom 0)
              before (:geschichte.commit/id (repo/head-commit conn))
              prepared (assoc (options conn journal)
                              :validate-basis! (fn [_] (swap! calls inc) true)
                              :before-dispatch! (fn [candidate]
                                                  (swap! journal conj candidate)
                                                  (repo/set-config! conn "after-validation" (str (random-uuid)))))
              result (files/write-to-completion! room system {"page.md" {:text text}}
                                                 {:publication prepared})]
          (is (some? (:error result)))
          (is (not= :returned (:completion result)))
          (is (= 1 @calls))
          (is (= 1 (count @journal)))
          (is (= before (:geschichte.commit/id (repo/head-commit conn))))
          (is (nil? (publication/recover @conn (:identity prepared)))))))))

(deftest lost-ack-recovers-before-fresh-semantic-validation-without-replaying
  (with-repository
    (fn [room system conn]
      (let [journal (atom []) calls (atom 0) original publication/publish!
            prepared (assoc (options conn journal) :validate-basis! (fn [_] (swap! calls inc) true))
            first-result (with-redefs [publication/publish!
                                       (fn [& args]
                                         (apply original args)
                                         (throw (ex-info "Lost publication ACK" {::publication/uncertain? true})))]
                           (files/write-to-completion! room system {"page.md" {:text "accepted"}}
                                                       {:publication prepared}))]
        (is (some? (:error first-result)))
        (is (= :pending (:completion first-result)))
        (is (= 1 @calls))
        (is (= 1 (count @journal)))
        (files/write-to! room system {"page.md" {:text "later human edit"}} {})
        (let [before (repo/worktree conn)
              recovered (files/write-to-completion!
                         room system {"page.md" {:text "must not replay"}}
                         {:publication (assoc prepared :validate-basis!
                                              (fn [_] (throw (AssertionError. "Historical receipt must recover first"))))})]
          (is (= :returned (:completion recovered)))
          (is (true? (get-in recovered [:result :recovered?])))
          (is (= before (repo/worktree conn)))
          (is (= 1 @calls))
          (is (= 1 (count @journal))))))))
