(ns dvergr.chat.compaction-test
  "Tests for memory compaction functionality."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.chat.compaction :as compaction]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.schema :as schema]
            [datahike.api :as dh]))

;; ============================================================================
;; Token Estimation Tests
;; ============================================================================

(deftest estimate-tokens-test
  (testing "Token estimation from character count"
    (is (= 1 (compaction/estimate-tokens "abc")))
    (is (= 3 (compaction/estimate-tokens "abcdefghijkl")))
    (is (= 0 (compaction/estimate-tokens "")))
    (is (= 0 (compaction/estimate-tokens nil)))))

(deftest message-tokens-test
  (testing "Get tokens from message with stored count"
    (let [msg {:message/tokens 100
               :message/content "This has way more than 100 characters but we use stored count"}]
      (is (= 100 (compaction/message-tokens msg)))))

  (testing "Estimate tokens when not stored"
    (let [msg {:message/content "test"}]
      (is (= 1 (compaction/message-tokens msg))))))

(deftest total-context-tokens-test
  (testing "Sum tokens across messages"
    (let [messages [{:message/tokens 10}
                    {:message/tokens 20}
                    {:message/content "test"}]]  ; 1 token estimated
      (is (= 31 (compaction/total-context-tokens messages))))))

;; ============================================================================
;; Compaction Detection Tests
;; ============================================================================

(deftest should-compact-test
  (testing "Compaction needed when over threshold"
    ;; should-compact? uses total-context-tokens which reads :message/tokens.
    ;; Threshold = 128000 * 0.7 = 89600. prune-minimum = 10000.
    ;; We need total tokens > 89600 AND tokens-to-remove > 10000.
    (let [ctx (chat-ctx/create-chat-context {:title "test" :budget 100000})]
      ;; Add 7 messages with high token counts to exceed threshold
      (dotimes [i 7]
        (chat-ctx/add-message! ctx {:role :user
                                    :content (str "test " i)
                                    :tokens 15000}))

      (is (compaction/should-compact? ctx))))

  (testing "No compaction when under threshold"
    (let [ctx (chat-ctx/create-chat-context {:title "test" :budget 100000})]
      (dotimes [i 7]
        (chat-ctx/add-message! ctx {:role :user :content (str "test " i)}))

      (is (not (compaction/should-compact? ctx))))))

;; ============================================================================
;; Wiki-Link Extraction Tests
;; ============================================================================

(deftest extract-wiki-links-test
  (testing "Extract wiki-links from text"
    (is (= [{:entity "Entity" :context "context about entity"}]
           (compaction/extract-wiki-links "We discussed [[Entity][context about entity]] today.")))

    (is (= [{:entity "Foo" :context "bar"}
            {:entity "Baz" :context "qux"}]
           (compaction/extract-wiki-links "[[Foo][bar]] and [[Baz][qux]]")))

    (is (empty? (compaction/extract-wiki-links "No links here")))))

(deftest extract-all-wiki-links-test
  (testing "Extract wiki-links from message list"
    (let [messages [{:message/content "[[Entity1][info1]]"}
                    {:message/content "[[Entity2][info2]]"}
                    {:message/content "No links"}]
          result (compaction/extract-all-wiki-links messages)]
      (is (= 2 (count result)))
      (is (some #(= "Entity1" (:entity %)) result))
      (is (some #(= "Entity2" (:entity %)) result))
      (is (every? #(= 1 (:mention-count %)) result)))))

;; ============================================================================
;; Message Selection Tests
;; ============================================================================

(deftest select-for-compaction-test
  (testing "Select messages for compaction"
    (let [messages [{:message/role :system :message/content "sys" :message/tokens 10}
                    {:message/role :user :message/content "u1" :message/tokens 10}
                    {:message/role :assistant :message/content "a1" :message/tokens 10}
                    {:message/role :user :message/content "u2" :message/tokens 10 :message/important? true}
                    {:message/role :assistant :message/content "a2" :message/tokens 10}
                    {:message/role :tool-result :message/content "result" :message/tokens 10}]
          selected (compaction/select-for-compaction messages 15)]

      ;; Should select some for compaction (returns vector of selected messages)
      (is (vector? selected))

      ;; When requesting 15 tokens, should select messages
      ;; (actual selection depends on scoring logic)
      (is (every? #(contains? % :msg) selected)))))

;; ============================================================================
;; Active Messages Tests
;; ============================================================================

(deftest get-active-messages-test
  (testing "Get messages after compaction point"
    (let [ctx (chat-ctx/create-chat-context {:title "test"})]
      ;; Add some messages
      (chat-ctx/add-message! ctx {:role :user :content "msg1"})
      (chat-ctx/add-message! ctx {:role :assistant :content "resp1"})

      ;; Get all messages (no compaction yet)
      (let [active (compaction/get-active-messages ctx)]
        (is (= 2 (count active)))
        (is (= "msg1" (:message/content (first active)))))))

  (testing "Filter messages before compaction point"
    (let [ctx (chat-ctx/create-chat-context {:title "test"})]
      ;; Add old messages that will be compacted
      (chat-ctx/add-message! ctx {:role :user :content "old"})
      (chat-ctx/add-message! ctx {:role :assistant :content "old-resp"})

      ;; Add compaction message (simulates what compact! does)
      (chat-ctx/add-message! ctx {:role :system
                                  :content "=== Context Summary ===\nOld messages compacted"
                                  :compacted? true
                                  :important? true})

      ;; Add new message after compaction
      (chat-ctx/add-message! ctx {:role :user :content "new"})

      ;; Should get messages from compaction point onwards
      (let [active (compaction/get-active-messages ctx)]
        ;; Should have: compaction message + new message
        (is (>= (count active) 2))
        (is (some #(= "new" (:message/content %)) active))
        ;; Should have compaction message
        (is (some #(and (= :system (:message/role %))
                        (:message/compacted? %))
                  active))))))


;; These regressions use a plain message store and a deterministic summarizer.
;; They neither create an execution world nor invoke a provider.
(defn- fixture-message [role content tokens]
  {:message/id (random-uuid) :message/role role
   :message/content content :message/tokens tokens})

(defn- append-fixture-message! [messages opts]
  (let [msg (schema/create-message-entity (assoc opts :chat-id (random-uuid)))]
    (swap! messages conj msg)
    msg))

(deftest exact-compaction-coverage-preserves-unselected-history-test
  (let [system (fixture-message :system "canonical instructions" 1)
        user1 (fixture-message :user "old request" 1)
        assistant1 (fixture-message :assistant "unselected answer" 1)
        result1 (fixture-message :tool-result "large old result" 100)
        tail (mapv #(fixture-message %1 %2 1)
                   [:user :assistant :user :assistant]
                   ["second request" "second answer" "latest request" "latest answer"])
        original (into [system user1 assistant1 result1] tail)
        messages (atom original)
        seen (atom nil)]
    (with-redefs [chat-ctx/get-messages (fn [_] @messages)
                  chat-ctx/add-message! (fn [_ opts] (append-fixture-message! messages opts))
                  compaction/compaction-needed-tokens (fn [& _] 50)]
      (compaction/compact! {} :summarizer (fn [selected _] (reset! seen selected) "checkpoint"))
      (let [summary (peek @messages)
            active (compaction/get-active-messages {})]
        (is (= [result1] @seen))
        (is (= [(:message/id result1)] (:message/compacted-message-ids summary)))
        (is (= (conj (vec (remove #{result1} original)) summary) active))
        (is (= original (subvec @messages 0 (count original)))
            "Raw original history remains intact")
        (is (= tail (vec (filter (set tail) active))))))))

(deftest repeated-compaction-preserves-prior-summary-and-coverage-test
  (let [old-result (fixture-message :tool-result "old result" 100)
        old-answer (fixture-message :assistant "old answer" 100)
        messages (atom [old-result old-answer
                        (fixture-message :user "middle user" 1)
                        (fixture-message :assistant "middle answer" 1)
                        (fixture-message :user "latest user" 1)
                        (fixture-message :assistant "latest answer" 1)])]
    (with-redefs [chat-ctx/get-messages (fn [_] @messages)
                  chat-ctx/add-message! (fn [_ opts] (append-fixture-message! messages opts))
                  compaction/compaction-needed-tokens (fn [& _] 50)]
      (compaction/compact! {} :summarizer (fn [_ _] "first checkpoint"))
      (let [first-summary (peek @messages)]
        (compaction/compact! {} :summarizer (fn [selected _]
                                             (is (= [old-answer] selected))
                                             "second checkpoint"))
        (let [second-summary (peek @messages)
              active (compaction/get-active-messages {})]
          (is (= [(:message/id old-result)] (:message/compacted-message-ids first-summary)))
          (is (= [(:message/id old-answer)] (:message/compacted-message-ids second-summary)))
          (is (some #{first-summary} active) "Prior checkpoint ancestry remains available")
          (is (some #{second-summary} active))
          (is (not-any? #{old-result old-answer} active))
          (is (= 6 (count active)))
          (is (= 8 (count @messages))))))))

(deftest tool-call-groups-and-source-order-test
  (let [call-id "call-1"
        assistant (assoc (fixture-message :assistant "call" 1)
                         :message/tool-uses [{:tool-use/id call-id}])
        result (assoc (fixture-message :tool-result "result" 100)
                      :message/tool-use-id call-id)
        earlier-user (fixture-message :user "original question" 1)
        tail [(fixture-message :user "recent 1" 1)
              (fixture-message :assistant "answer 1" 1)
              (fixture-message :user "recent 2" 1)
              (fixture-message :assistant "answer 2" 1)]
        messages (into [earlier-user assistant result] tail)]
    (is (= [assistant result] (mapv :msg (compaction/select-for-compaction messages 1)))
        "A verbose result cannot be removed without its assistant tool call")
    (is (= [earlier-user assistant result]
           (mapv :msg (compaction/select-for-compaction messages 1000)))
        "Priority selection is restored to chronological order")
    (is (= [earlier-user]
           (mapv :msg (compaction/select-for-compaction
                        (assoc messages 1 (assoc assistant :message/important? true)) 1)))
        "An important call also protects its results")
    (is (empty? (compaction/select-for-compaction
                  [assistant (fixture-message :user "recent 1" 1) result
                   (fixture-message :user "recent 2" 1)] 1))
        "A group crossing the protected boundary remains intact")))

(deftest pruning-recomputes-active-token-count-test
  (let [old (fixture-message :tool-result "large result" 1000)
        tail [(fixture-message :user "recent 1" 1)
              (fixture-message :user "recent 2" 1)]
        original (into [old] tail)
        [pruned recovered] (compaction/prune-tool-outputs original)
        marker (first pruned)]
    (is (= 1000 (:message/original-tokens marker)))
    (is (= (compaction/estimate-tokens (:message/content marker))
           (compaction/message-tokens marker)))
    (is (= recovered (- (compaction/total-context-tokens original)
                        (compaction/total-context-tokens pruned))))
    (is (= tail (subvec pruned 1)))))

(deftest coverage-metadata-survives-message-construction-test
  (let [ids [(random-uuid) (random-uuid)]
        summary (compaction/create-compaction-message "summary" [] ids)
        entity (schema/create-message-entity {:chat-id (random-uuid)
                                             :role (:message/role summary)
                                             :content (:message/content summary)
                                             :compacted? true
                                             :compacted-message-ids ids})]
    (is (= ids (:message/compacted-message-ids summary)))
    (is (= ids (:message/compacted-message-ids entity)))
    (is (= :db.type/uuid (:db/valueType (first (filter #(= :message/compacted-message-ids (:db/ident %))
                                                               schema/message-schema)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact original message identities"
                         (compaction/create-compaction-message "summary" [] [nil])))))

(deftest missing-coverage-does-not-discard-history-test
  (let [original (fixture-message :user "still required" 1)
        marker (assoc (fixture-message :system "old checkpoint" 1) :message/compacted? true)
        messages [original marker]]
    (with-redefs [chat-ctx/get-messages (fn [_] messages)]
      (let [active (compaction/get-active-messages {})]
        (is (= messages active))
        (is (= [(:message/id marker)] (:compaction/coverage-unavailable-ids (meta active))))))))

(deftest maybe-compact-respects-model-context-window-test
  (let [messages (atom (mapv #(fixture-message :user (str "request " %) 5000) (range 7)))
        seen (atom [])]
    (with-redefs [chat-ctx/get-messages (fn [_] @messages)
                  chat-ctx/add-message! (fn [_ opts] (append-fixture-message! messages opts))]
      (is (not (compaction/should-compact? {})))
      (is (compaction/should-compact? {} :context-window 40000))
      (is (pos? (:compacted-count
                  (compaction/maybe-compact! {} :context-window 40000
                    :summarizer (fn [selected _] (swap! seen conj selected) "checkpoint")))))
      (is (= 1 (count @seen))))))


(deftest overlapping-tool-groups-share-protection-and-token-accounting-test
  (let [call (fn [ids]
               (assoc (fixture-message :assistant "tool calls" 1)
                      :message/tool-uses (mapv #(hash-map :tool-use/id %) ids)))
        result (fn [id]
                 (assoc (fixture-message :tool-result "tool result" 100)
                        :message/tool-use-id id))
        first-call (call ["shared-x"])
        bridging-call (call ["shared-x" "shared-y"])
        protected-call (assoc (call ["shared-y"]) :message/important? true)
        result-x (result "shared-x")
        result-y (result "shared-y")
        tail [(fixture-message :user "recent 1" 1)
              (fixture-message :user "recent 2" 1)]]
    (is (empty? (compaction/select-for-compaction
                  (into [first-call bridging-call protected-call result-x result-y] tail) 1))
        "Transitive shared-result groups inherit important-message protection")
    (is (empty? (compaction/select-for-compaction
                  [first-call bridging-call result-x
                   (fixture-message :user "recent 1" 1) (call ["shared-y"]) result-y
                   (fixture-message :user "recent 2" 1)] 1))
        "A recent call protects every group connected through a reused ID")
    (let [second-call (call ["shared-x"])
          additional (fixture-message :assistant "additional older answer" 100)
          selected (compaction/select-for-compaction
                     (into [first-call second-call result-x additional] tail) 150)]
      (is (= [first-call second-call result-x additional] (mapv :msg selected))
          "A shared result counts once; another message is needed to reach the target")
      (is (= 202 (reduce + (map :tokens selected)))))))

(deftest covered-identities-roundtrip-through-disposable-datahike-test
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? false}
        chat-id (random-uuid)
        originals (mapv #(schema/create-message-entity {:chat-id chat-id :role :user :content %})
                        ["first original" "second original"])
        covered-ids (mapv :message/id originals)
        summary (schema/create-message-entity
                  {:chat-id chat-id :role :system :content "checkpoint"
                   :important? true :compacted? true :compacted-message-ids covered-ids})]
    (dh/create-database config)
    (try
      (let [conn (dh/connect config)]
        (try
          (schema/ensure-full-schema! conn)
          (dh/transact conn [{:chat/id chat-id}])
          (dh/transact conn (conj originals summary))
          (let [db @conn
                read-message (fn [message-id]
                               (let [eid (dh/q '[:find ?message . :in $ ?id
                                                 :where [?message :message/id ?id]]
                                               db message-id)]
                                 (dh/pull db '[*] eid)))
                restored (mapv (comp read-message :message/id) (conj originals summary))
                restored-summary (peek restored)]
            (is (= (set covered-ids) (set (:message/compacted-message-ids restored-summary))))
            (is (every? uuid? (:message/compacted-message-ids restored-summary)))
            (with-redefs [chat-ctx/get-messages (fn [_] restored)]
              (is (= [restored-summary] (compaction/get-active-messages {})))
              (is (nil? (:compaction/coverage-unavailable-ids
                          (meta (compaction/get-active-messages {})))))))
          (finally (dh/release conn))))
      (finally (dh/delete-database config)))))
