(ns dvergr.room.store.contract
  "Behavioral contract shared by every PRoomStore implementation."
  (:require [clojure.test :refer [is testing]]
            [dvergr.room.store :as store]))

(defn assert-message-envelope!
  "Exercise lossless envelope replay and first-write-wins idempotence."
  [st room-id]
  (testing "message envelope round-trips"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          grandchild-id (random-uuid)
          other-id (random-uuid)
          ts 1787860800123
          metadata {:role :user
                    :source-user "Alice"
                    :object {:kind :proposal :id (random-uuid)}
                    :attachment {:blob-id (random-uuid) :mime "audio/ogg"}
                    :audience #{:agent/reviewer}
                    :provenance {:mode :live :source :screen}}
          parent {:id parent-id :from :party/alice :to nil
                  :content "proposal" :ts (dec ts) :role :user
                  :thread-root-id parent-id
                  :metadata {:role :user :source-user "Alice"}}
          child {:id child-id :from :party/alice :to :agent/reviewer
                 :content "please review" :ts ts :in-reply-to parent-id
                 :thread-root-id parent-id
                 :role :user :metadata metadata}
          grandchild {:id grandchild-id :from :agent/reviewer :to :party/alice
                      :content "one question" :ts (inc ts) :in-reply-to child-id
                      :thread-root-id parent-id
                      :role :user :metadata metadata}
          other {:id other-id :from :party/alice :to :agent/reviewer
                 :content "unrelated" :ts (+ ts 2) :thread-root-id other-id
                 :role :user :metadata metadata}]
      (store/-store-room! st room-id {:slug (name room-id) :title "Contract"})
      ;; Imports and distributed replay may see a reply before its parent. The
      ;; stable UUID field must not require the target entity to exist yet.
      (is (= :inserted (store/-store-message! st room-id child)))
      (is (= :inserted (store/-store-message! st room-id grandchild)))
      (is (= :inserted (store/-store-message! st room-id parent)))
      (is (= :inserted (store/-store-message! st room-id other)))
      ;; A retry carrying mutated data must not rewrite durable history.
      (is (= :duplicate
             (store/-store-message! st room-id
                                    (assoc child :content "mutated retry"))))
      (let [messages (store/-list-messages st room-id {})
            replayed (some #(when (= child-id (:id %)) %) messages)
            replayed-grandchild (some #(when (= grandchild-id (:id %)) %) messages)
            envelope-keys [:id :from :to :content :ts :in-reply-to
                           :thread-root-id :role]]
        (is (= (select-keys child envelope-keys)
               (select-keys replayed envelope-keys)))
        (is (= (select-keys grandchild envelope-keys)
               (select-keys replayed-grandchild envelope-keys)))
        (is (= metadata (:metadata replayed)))
        (is (= parent-id
               (store/-message-thread-root st room-id child-id)))
        (is (= #{parent-id child-id grandchild-id}
               (set (map :id (store/-list-messages
                              st room-id {:thread-root-id parent-id}))))
            "thread query excludes another top-level topic before limiting")))))

(defn assert-run-lifecycle!
  "Exercise durable Run creation, update, lookup, filtering, and identity safety."
  [st room-id]
  (testing "run lifecycle round-trips"
    (store/-store-room! st room-id {:slug (name room-id) :title "Runs"})
    (let [run-id (random-uuid)
          trigger-id (random-uuid)
          parent-id (random-uuid)
          started (java.util.Date. 1787860800000)
          ended (java.util.Date. 1787860801000)
          definition-hash (random-uuid)
          chat-id (random-uuid)
          world-id :runs_fork_fork-contract
          running {:run/id run-id
                   :run/kind :agent-turn
                   :run/room room-id
                   :run/actor :agent/researcher
                   :run/trigger trigger-id
                   :run/parent parent-id
                   :run/roster :research-team
                   :run/agent-version 3
                   :run/program-kind :llm
                   :run/interpreter-version 2
                   :run/agent-def-hash definition-hash
                   :run/chat-id chat-id
                   :run/world world-id
                   :run/isolation :ctx
                   :run/settlement-policy :automatic
                   :run/settlement-status :open
                   :run/status :running
                   :run/created-at started
                   :run/started-at started
                   :run/updated-at started}
          completed (assoc running
                           :run/status :completed
                           :run/settlement-status :merged
                           :run/updated-at ended
                           :run/ended-at ended)]
      (is (= running (store/-store-run! st room-id running)))
      (is (= running (store/-load-run st room-id run-id)))
      (is (= completed (store/-store-run! st room-id completed)))
      (is (= [completed] (store/-list-runs st room-id {:actor :agent/researcher})))
      (is (empty? (store/-list-runs st room-id {:status :failed})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"immutable"
           (store/-store-run! st room-id (assoc completed :run/trigger (random-uuid)))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #":run/chat-id must be a UUID"
           (store/-store-run! st room-id (assoc completed :run/chat-id :not-a-uuid)))))))

(defn assert-attention-projection!
  "Exercise durable participant-specific attention storage and filtering."
  [st room-id]
  (testing "attention is durable control state, separate from speech"
    (store/-store-room! st room-id {:slug (name room-id) :title "Attention"})
    (let [created (java.util.Date. 1787860800000)
          fact {:attention/id (random-uuid)
                :attention/participant :agent/researcher
                :attention/message-id (random-uuid)
                :attention/run-id (random-uuid)
                :attention/memory :remember
                :attention/activation :none
                :attention/control :continue
                :attention/at :next-safe-boundary
                :attention/priority 0.0
                :attention/status :ready
                :attention/reason :peer/observation
                :attention/metadata {:classifier :test :confidence 0.75}
                :attention/created-at created}]
      (is (= fact (store/-store-attention! st room-id fact)))
      (is (= [fact]
             (store/-list-attention st room-id
                                    {:participant :agent/researcher})))
      (is (= [fact] (store/unapplied-attention [fact])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"immutable"
           (store/-store-attention! st room-id
                                    (assoc fact :attention/reason :mutated/retry))))
      (is (empty? (store/-list-messages st room-id {}))
          "attention facts never contaminate the Room transcript")
      (is (empty? (store/-list-attention st room-id
                                         {:participant :agent/other})))
      (let [applied (-> fact
                        (assoc :attention/id (random-uuid)
                               :attention/decision-id (:attention/id fact)
                               :attention/status :applied
                               :attention/created-at (java.util.Date. 1787860800001)))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"missing decision"
             (store/-store-attention!
              st room-id (assoc applied
                                :attention/id (random-uuid)
                                :attention/decision-id (random-uuid)))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"differ"
             (store/-store-attention!
              st room-id (assoc applied
                                :attention/id (random-uuid)
                                :attention/message-id (random-uuid)))))
        (store/-store-attention! st room-id applied)
        (is (empty? (store/unapplied-attention [fact applied])))))))

(defn assert-concurrent-attention-identity!
  "Conflicting writers must agree on one immutable attention identity."
  [st room-id]
  (testing "attention first-write identity is atomic"
    (store/-store-room! st room-id {:slug (name room-id) :title "Attention race"})
    (let [attention-id (random-uuid)
          base {:attention/id attention-id
                :attention/participant :agent/researcher
                :attention/message-id (random-uuid)
                :attention/memory :remember
                :attention/status :ready
                :attention/created-at (java.util.Date.)}
          ready (java.util.concurrent.CountDownLatch. 2)
          start (promise)
          writer (fn [reason]
                   (future
                     (.countDown ready)
                     @start
                     (try
                       (store/-store-attention! st room-id
                                                (assoc base :attention/reason reason))
                       (catch Throwable error error))))
          a (writer :race/a)
          b (writer :race/b)]
      (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
      (deliver start true)
      (let [outcomes [@a @b]]
        (is (= 1 (count (filter map? outcomes))))
        (is (= 1 (count (filter #(instance? Throwable %) outcomes))))
        (is (= 1 (count (store/-list-attention st room-id {}))))))))

(defn assert-cross-room-attention-identity!
  "One global fact identity cannot be acknowledged under a second Room."
  [st]
  (testing "attention identity includes and validates Room ownership"
    (let [room-a :attention-room-a
          room-b :attention-room-b
          fact {:attention/id (random-uuid)
                :attention/participant :agent/researcher
                :attention/message-id (random-uuid)
                :attention/memory :remember
                :attention/status :ready
                :attention/created-at (java.util.Date.)}]
      (store/-store-room! st room-a {:slug (name room-a) :title "A"})
      (store/-store-room! st room-b {:slug (name room-b) :title "B"})
      (store/-store-attention! st room-a fact)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"immutable"
                            (store/-store-attention! st room-b fact)))
      (is (empty? (store/-list-attention st room-b {}))))))

(defn assert-attention-metadata-validation!
  [st room-id]
  (testing "durable attention metadata must round-trip as EDN"
    (store/-store-room! st room-id {:slug (name room-id) :title "Metadata"})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"round-trippable EDN"
         (store/-store-attention!
          st room-id
          {:attention/id (random-uuid)
           :attention/participant :agent/researcher
           :attention/message-id (random-uuid)
           :attention/status :ready
           :attention/metadata {:callback (fn [])}
           :attention/created-at (java.util.Date.)})))))
