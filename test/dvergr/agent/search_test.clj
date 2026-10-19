(ns dvergr.agent.search-test
  "A search: alternatives of one task in their own worlds, weighed, joined by
   a policy, and recorded on every member's Run (doc/design/runs-as-worlds.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.agent.search :as search]
            [dvergr.agent.workflow :as workflow]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.rooms.forks :as forks]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest decisions-follow-the-weights
  (let [w [-1.0 -0.2 ##-Inf -0.2 -3.0]]
    (testing ":automatic merges the heaviest possible member, the earlier on a tie"
      (is (= [:discard :merge :discard :discard :discard] (search/decide w {:policy :automatic}))))
    (testing ":retain keeps the next-heaviest for inspection"
      (is (= [:review :merge :discard :review :discard] (search/decide w {:policy :automatic :retain 2}))))
    (testing ":review hands the heaviest to a person"
      (is (= [:discard :review :discard :discard :discard] (search/decide w {:policy :review}))))
    (testing ":keep-all keeps every member"
      (is (every? #{:review} (search/decide w {:policy :keep-all}))))
    (testing "an impossible member is never selected or retained"
      (is (= [:discard :discard] (search/decide [##-Inf ##-Inf] {:policy :automatic :retain 1}))))
    (testing "unknown policies and retentions are refused"
      (is (thrown? clojure.lang.ExceptionInfo (search/decide w {:policy :best})))
      (is (thrown? clojure.lang.ExceptionInfo (search/decide w {:retain -1}))))))

(deftest the-default-weight-is-the-log-reward
  (is (= 0.0 (search/reward-weight {:attempt {:attempt/receipt {:attempt/reward 1.0}}})))
  (is (= ##-Inf (search/reward-weight {:attempt {:attempt/receipt {:attempt/reward 0.0}}}))))

(defmacro ^:private with-stub-model [& body]
  `(with-redefs [providers/ensure-initialized! (constantly nil)
                 chat-agent/messages->api-format (fn [messages# _# _#] messages#)
                 model-chat/chat (fn [_# _#]
                                   {:content "done: nothing to change"
                                    :tool-calls nil
                                    :usage {:input-tokens 100 :output-tokens 10}
                                    :stop-reason :end-turn})]
     ~@body))

(defn- durable-search [room run-id]
  (run/search-record (store/-load-run (:store room) (store/conversation-id room) run-id)))

(defn- with-room [id f]
  (let [room (d/make-room {:id id :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)] (rreg/register! room))
      (with-stub-model (f room))
      (finally
        ;; Review worlds intentionally remain live after these assertions. This
        ;; fixture owns the whole root, so drain all owners before releasing it;
        ;; shutdown does not claim that those proposals merged or were discarded.
        (d/close-shutdown-contexts! (d/close-registered-rooms! (:ctx room)))))))

(deftest a-search-merges-one-world-and-records-every-member
  (with-room :search-test
    (fn [room]
      (let [;; three alternatives, weighed by a judge: the second is best
            weights (atom [-2.0 -0.1 -1.0])
            weigh (fn [_] (let [w (first @weights)] (swap! weights subvec 1) w))
            outcome (search/search! room {:task "tidy the notes" :attempts 3
                                          :models ["claude-haiku-4-5"]
                                          :weigh weigh :retain 1})
            members (:search/members outcome)
            by-decision (group-by :search/decision members)]
        (testing "the heaviest member's world was merged, the next kept, the last discarded"
          (is (= [:discard :merge :review] (mapv :search/decision members)))
          (is (= (:run/id (second members)) (:search/selected outcome)))
          (is (every? #(= :settled (:search/status %)) members)))
        (binding [ec/*execution-context* (:ctx room)]
          (testing "only the retained world is still open"
            (is (some? (rreg/lookup (:world (first (:review by-decision))))))
            (is (nil? (rreg/lookup (:world (first (:merge by-decision))))))
            (is (nil? (rreg/lookup (:world (first (:discard by-decision))))))))
        (testing "every member's Run records the search, its weight and its decision"
          (doseq [{:keys [run/id search/weight search/decision]} members]
            (is (= {:search/id (:search/id outcome) :search/policy :automatic :search/members 3
                    :search/weight weight :search/decision decision :search/status :settled
                    :search/outcome ({:merge :merged :review :review :discard :discarded} decision)}
                   (dissoc (durable-search room id) :search/manifest)))
            (is (= 3 (count (:search/manifest (durable-search room id)))) "every record carries the manifest")))
        (testing "the Runs' settlement follows the decision"
          (let [status #(:run/settlement-status
                         (store/-load-run (:store room) (store/conversation-id room) (:run/id %)))]
            (is (= :merged (status (first (:merge by-decision)))))
            (is (= :discarded (status (first (:discard by-decision)))))
            (is (= :review (status (first (:review by-decision)))))))))))

(deftest a-search-with-no-possible-member-merges-nothing
  (with-room :search-none-test
    (fn [room]
      (let [outcome (search/search! room {:task "tidy" :attempts 2 :models ["claude-haiku-4-5"]
                                          :policy :review :weigh (constantly ##-Inf)})]
        (is (nil? (:search/selected outcome)))
        (is (= [:discard :discard] (mapv :search/decision (:search/members outcome))))
        (is (every? #(= ##-Inf (:search/weight (durable-search room (:run/id %))))
                    (:search/members outcome))
            "an impossible weight survives the EDN round trip")))))

(deftest invalid-options-run-nothing
  (with-room :search-invalid-test
    (fn [room]
      (let [calls (atom 0)]
        (with-redefs [model-chat/chat (fn [_ _] (swap! calls inc) (throw (ex-info "no call expected" {})))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (search/search! room {:task "t" :attempts 2 :models ["claude-haiku-4-5"] :policy :best})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (search/search! room {:task "t" :attempts 2 :models ["claude-haiku-4-5"] :retain -1}))))
        (is (zero? @calls) "no attempt ran")))))

(deftest members-the-search-does-not-hold-are-refused-before-anything-settles
  (with-room :search-foreign-test
    (fn [room]
      ;; ordinary attempts: released to review, not held by a search
      (let [rows (workflow/attempt! room {:task "t" :attempts 2 :models ["claude-haiku-4-5"]})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not held by the search"
                              (search/select! room rows {:policy :automatic})))
        (binding [ec/*execution-context* (:ctx room)]
          (is (every? #(some? (rreg/lookup (:world %))) rows) "no world was settled"))
        (is (every? #(nil? (durable-search room (get-in % [:attempt :attempt/receipt :attempt/run-id]))) rows)
            "nothing was recorded")))))

(deftest a-search-retried-with-its-id-leaves-settled-members-alone
  (with-room :search-retry-test
    (fn [room]
      (let [members (workflow/attempt! room {:task "t" :attempts 2 :models ["claude-haiku-4-5"]
                                             :hold? true :effects search/speculative-effects})
            first-run (search/select! room members {:policy :review})
            again (search/select! room members {:policy :review :search-id (:search/id first-run)})]
        (is (= (map (juxt :search/decision :search/status) (:search/members first-run))
               (map (juxt :search/decision :search/status) (:search/members again))))
        (is (= [:review :discard] (mapv :search/decision (:search/members again))))))))

(deftest search-records-are-strings-and-survive-updates-that-omit-them
  (let [s (memory/make)
        room-id "parity-room"
        run {:run/id (random-uuid) :run/kind :llm :run/room :r :run/actor :a
             :run/trigger (random-uuid) :run/status :running
             :run/created-at (java.util.Date.) :run/started-at (java.util.Date.)
             :run/updated-at (java.util.Date.)}]
    (is (thrown? clojure.lang.ExceptionInfo (store/-store-run! s room-id (assoc run :run/search {:not "a string"}))))
    (store/-store-run! s room-id (assoc run :run/search "{:search/id 1}"))
    (store/-store-run! s room-id (assoc run :run/updated-at (java.util.Date.)))
    (is (= "{:search/id 1}" (:run/search (store/-load-run s room-id (:run/id run)))))))

(deftest effects-can-only-narrow-the-speculative-filter
  (is (= search/speculative-effects (search/speculative nil)))
  (is (= search/speculative-effects (search/speculative {})))
  (is (= {:admit #{:read} :speculative true} (search/speculative {:admit #{:read :egress}})))
  (is (= {:admit #{:read :write :spend :network} :speculative true :record true}
         (search/speculative {:record true}))))

(defn- held [room n effects]
  (workflow/attempt! room (cond-> {:task "t" :attempts n :models ["claude-haiku-4-5"] :hold? true}
                            effects (assoc :effects effects))))

(deftest members-must-be-held-under-the-speculative-filter-and-match-their-runs
  (with-room :search-preflight-test
    (fn [room]
      (testing "a member held without the speculative filter is refused"
        (let [members (held room 2 nil)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"speculative effect filter"
                                (search/select! room members {:policy :review})))))
      (testing "swapped worlds are refused"
        (let [[a b] (held room 2 search/speculative-effects)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not its Run's world"
                                (search/select! room [(assoc a :world (:world b)) (assoc b :world (:world a))]
                                                {:policy :review}))))))))

(deftest a-retry-keeps-the-recorded-decisions-and-a-run-joins-one-search
  (with-room :search-retry2-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            first-run (search/select! room members {:policy :review :weigh (fn [_] -1.0)})
            id (:search/id first-run)]
        (testing "a retry with another judge keeps what was decided"
          (is (= (mapv (juxt :search/weight :search/decision) (:search/members first-run))
                 (mapv (juxt :search/weight :search/decision)
                       (:search/members (search/select! room members {:policy :review :search-id id
                                                                      :weigh (fn [_] 5.0)}))))))
        (testing "a retry with another policy is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same members and policy"
                                (search/select! room members {:policy :automatic :search-id id}))))
        (testing "a Run claimed by one search is refused by another"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"member of another search"
                                (run/record-search! room (:run/id (first (:search/members first-run)))
                                                    {:search/id (random-uuid)}))))))))

(deftest concurrent-selections-of-one-search-merge-one-world
  (with-room :search-concurrent-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            id (random-uuid)
            start (promise)
            run (fn [w] (future @start (search/select! room members {:policy :automatic :search-id id
                                                                     :weigh (fn [m] (if (= m (first members)) w (- w)))})))
            a (run 1.0) b (run -1.0)]
        (deliver start true)
        (let [outcomes [@a @b]]
          (is (apply = (map :search/selected outcomes)) "both report the one winner")
          (is (= 1 (count (filter #(= :merged (:search/outcome %)) (:search/members (first outcomes)))))))))))

(deftest a-merge-whose-cleanup-failed-is-recorded-as-merged
  (with-room :search-incomplete-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            merge! forks/merge!
            outcome (with-redefs [forks/merge! (fn [fork & opts]
                                                 (apply merge! fork opts)
                                                 {:ok? false :error "cleanup failed"})]
                      (search/select! room members {:policy :automatic}))
            winner (some #(when (= :merge (:search/decision %)) %) (:search/members outcome))]
        (is (= [:merged :settled "cleanup failed"]
               ((juxt :search/outcome :search/status :search/error) winner))
            "its Run's settlement was written, so it is settled, with the error kept")
        (is (= (:run/id winner) (:search/selected outcome)) "the parent has its changes")
        (let [again (search/select! room members {:policy :automatic :search-id (:search/id outcome)})
              winner (some #(when (= :merge (:search/decision %)) %) (:search/members again))]
          (is (= [:merged :settled] ((juxt :search/outcome :search/status) winner))))))))

(deftest claims-are-all-or-none
  (with-room :search-claim-test
    (fn [room]
      (let [[a b c] (map #(get-in % [:attempt :attempt/receipt :attempt/run-id])
                         (held room 3 search/speculative-effects))]
        (run/claim-search! room {a {:search/id 1} b {:search/id 1}})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another search"
                              (run/claim-search! room {b {:search/id 2} c {:search/id 2}})))
        (is (nil? (durable-search room c)) "nothing of the losing claim was written")))))

(deftest when-every-member-is-impossible-nothing-merges
  (with-room :search-impossible-auto-test
    (fn [room]
      (let [outcome (search/select! room (held room 2 search/speculative-effects)
                                    {:policy :automatic :retain 1 :weigh (constantly ##-Inf)})]
        (is (nil? (:search/selected outcome)))
        (is (every? #(= :discarded (:search/outcome %)) (:search/members outcome)))))))

(deftest a-claim-left-partial-by-a-failed-write-stays-the-search-s
  (with-room :search-partial-claim-test
    (fn [room]
      (let [[a b c] (map #(get-in % [:attempt :attempt/receipt :attempt/run-id])
                         (held room 3 search/speculative-effects))
            store-claim! @#'run/store-claim!]
        (with-redefs-fn {#'run/store-claim! (fn [s rid existing search]
                                              (if (= b (:run/id existing))
                                                (throw (ex-info "storage failed" {}))
                                                (store-claim! s rid existing search)))}
          #(is (thrown? Exception (run/claim-search! room {a {:search/id 10} b {:search/id 10}}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reserved by another search"
                              (run/claim-search! room {b {:search/id 11} c {:search/id 11}})))
        (run/claim-search! room {a {:search/id 10} b {:search/id 10}})
        (is (= 10 (:search/id (durable-search room b))) "the first search resumes its claim")))))

(deftest a-search-id-names-one-set-of-members
  (with-room :search-disjoint-test
    (fn [room]
      (let [[a b c d] (held room 4 search/speculative-effects)
            id (random-uuid)]
        (search/select! room [a b] {:policy :review :search-id id})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one set of members"
                              (search/select! room [c d] {:policy :review :search-id id})))))))

(deftest a-failed-settlement-write-after-a-merge-is-projected-again
  (with-room :search-projection-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            update! run/update-durable-settlement!
            failures (atom 2)
            outcome (with-redefs [run/update-durable-settlement!
                                  (fn [r rid status & more]
                                    (if (and (= :merged status) (pos? (swap! failures dec)))
                                      (throw (ex-info "settlement write failed" {}))
                                      (apply update! r rid status more)))]
                      (search/select! room members {:policy :automatic}))
            winner (some #(when (= :merge (:search/decision %)) %) (:search/members outcome))]
        (is (= :merged (:search/outcome winner)))
        (let [again (search/select! room members {:policy :automatic :search-id (:search/id outcome)})
              winner (some #(when (= :merge (:search/decision %)) %) (:search/members again))]
          (is (= [:merged :settled] ((juxt :search/outcome :search/status) winner)))
          (is (= :merged (:run/settlement-status
                          (store/-load-run (:store room) (store/conversation-id room) (:run/id winner))))))))))

(deftest a-member-of-another-search-is-refused-even-when-settled
  (with-room :search-foreign-settled-test
    (fn [room]
      (let [[a b] (held room 2 search/speculative-effects)
            _ (search/select! room [a b] {:policy :automatic})
            [c] (held room 1 search/speculative-effects)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"member of another search"
                              (search/select! room [a c] {:policy :automatic})))
        (is (nil? (durable-search room (get-in c [:attempt :attempt/receipt :attempt/run-id])))
            "the fresh member was not claimed")))))

(deftest a-merge-whose-world-stays-registered-is-incomplete
  (with-room :search-registered-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            lookup rreg/lookup
            kept (atom {})
            outcome (with-redefs [rreg/lookup (fn [id] (or (lookup id) (get @kept id)))
                                  forks/merge! (let [merge! forks/merge!]
                                                 (fn [fork & opts]
                                                   (swap! kept assoc (:id fork) fork)
                                                   (apply merge! fork opts)))]
                      (search/select! room members {:policy :automatic}))
            winner (some #(when (= :merge (:search/decision %)) %) (:search/members outcome))]
        (is (= [:merged :incomplete] ((juxt :search/outcome :search/status) winner))
            "cleanup did not finish: the world is still registered")
        (let [again (search/select! room members {:policy :automatic :search-id (:search/id outcome)})]
          (is (= [:merged :settled] ((juxt :search/outcome :search/status)
                                     (some #(when (= :merge (:search/decision %)) %) (:search/members again))))
              "once the world has left, a retry settles it"))))))

(deftest a-merge-whose-every-write-failed-is-reconciled-from-what-this-process-saw
  (with-room :search-sustained-failure-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            record! run/record-search!
            update! run/update-durable-settlement!
            outcome-writes-fail? (atom true)
            id (random-uuid)]
        (with-redefs [run/record-search! (fn [r rid search]
                                           (if (and @outcome-writes-fail? (not= :pending (:search/status search)))
                                             (throw (ex-info "storage down" {}))
                                             (record! r rid search)))
                      run/update-durable-settlement! (fn [r rid status & more]
                                                       (if (and @outcome-writes-fail? (= :merged status))
                                                         (throw (ex-info "storage down" {}))
                                                         (apply update! r rid status more)))]
          (is (thrown? Exception (search/select! room members {:policy :automatic :search-id id}))))
        (reset! outcome-writes-fail? false)
        (let [again (search/select! room members {:policy :automatic :search-id id})]
          (is (some #(= [:merged :settled] ((juxt :search/outcome :search/status) %)) (:search/members again))
              "the merged world is settled from this process's evidence"))))))

(deftest only-a-landed-merge-is-evidence
  (let [confirmed? @#'search/confirmed-merged?
        fork (fn [meta] {:meta (atom meta)})]
    (is (false? (confirmed? (fork {:dvergr.discourse/settlement-claim
                                   {:settlement {:op :merged} :active? true}})))
        "a claim in progress may abort")
    (is (false? (confirmed? (fork {:dvergr.discourse/settlement-claim
                                   {:settlement {:op :merged} :active? false}})))
        "an ended claim is not proof that all substrate mutation completed")
    (is (true? (confirmed? (fork {:dvergr.discourse/settled {:op :merged}}))))
    (is (false? (confirmed? (fork {:dvergr.discourse/settled {:op :discarded}}))))))

(deftest a-merge-whose-reads-and-writes-failed-is-reconciled-from-the-evidence
  (with-room :search-read-failure-test
    (fn [room]
      (let [members (held room 2 search/speculative-effects)
            durable-run @#'search/durable-run
            record! run/record-search!
            update! run/update-durable-settlement!
            down? (atom false)
            id (random-uuid)]
        (with-redefs-fn {#'search/durable-run (fn [r rid] (if @down? (throw (ex-info "storage down" {})) (durable-run r rid)))
                         #'run/record-search! (fn [r rid s] (if @down? (throw (ex-info "storage down" {})) (record! r rid s)))
                         #'run/update-durable-settlement! (fn [r rid status & more]
                                                            (when (= :merged status) (reset! down? true))
                                                            (if @down? (throw (ex-info "storage down" {}))
                                                                (apply update! r rid status more)))}
          #(is (thrown? Exception (search/select! room members {:policy :automatic :search-id id}))))
        (let [again (search/select! room members {:policy :automatic :search-id id})]
          (is (some #(= [:merged :settled] ((juxt :search/outcome :search/status) %)) (:search/members again))
              "the merged world is settled from the evidence kept before the store failed"))))))

(deftest a-world-is-checked-against-its-run-before-anything-is-written
  (with-room :search-evidence-mismatch-test
    (fn [room]
      (let [[a b] (held room 2 search/speculative-effects)
            settled (search/select! room [a b] {:policy :automatic})
            merged-world (:world (some #(when (= :merged (:search/outcome %)) %) (:search/members settled)))
            [c] (held room 1 search/speculative-effects)
            c-run (get-in c [:attempt :attempt/receipt :attempt/run-id])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not its Run's world"
                              (search/select! room [(assoc c :world merged-world)] {:policy :automatic})))
        (is (nil? (durable-search room c-run)) "nothing was written for the mismatched member")
        (binding [ec/*execution-context* (:ctx room)]
          (is (some? (rreg/lookup (:world c))) "its own world is still held"))))))

(deftest a-winner-settled-by-another-caller-is-recorded-as-it-ended
  (with-room :search-competing-test
    (fn [room]
      (testing "a competing discard between release and merge"
        (let [members (held room 2 search/speculative-effects)
              outcome (with-redefs [forks/merge! (fn [fork & _]
                                                   (forks/discard! fork)
                                                   {:ok? false :error "lost the race"})]
                        (search/select! room members {:policy :automatic}))
              winner (some #(when (= :merge (:search/decision %)) %) (:search/members outcome))]
          (is (= [:discarded :settled] ((juxt :search/outcome :search/status) winner)))
          (is (nil? (:search/selected outcome)))))
      (testing "a competing settlement still in progress stays pending"
        (let [members (held room 2 search/speculative-effects)
              outcome (with-redefs [forks/merge! (fn [fork & _]
                                                   (swap! (:meta fork) assoc :dvergr.discourse/settlement-claim
                                                          {:settlement {:op :merged} :active? true})
                                                   {:ok? false :error "busy"})]
                        (search/select! room members {:policy :automatic}))
              winner (some #(when (= :merge (:search/decision %)) %) (:search/members outcome))]
          (is (= :pending (:search/status winner)))
          (is (nil? (:search/outcome winner))))))))
