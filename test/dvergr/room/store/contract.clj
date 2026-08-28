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
      (store/-store-message! st room-id child)
      (store/-store-message! st room-id grandchild)
      (store/-store-message! st room-id parent)
      (store/-store-message! st room-id other)
      ;; A retry carrying mutated data must not rewrite durable history.
      (store/-store-message! st room-id (assoc child :content "mutated retry"))
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
          running {:run/id run-id
                   :run/kind :agent-turn
                   :run/room room-id
                   :run/actor :agent/researcher
                   :run/trigger trigger-id
                   :run/parent parent-id
                   :run/status :running
                   :run/created-at started
                   :run/started-at started
                   :run/updated-at started}
          completed (assoc running
                           :run/status :completed
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
           (store/-store-run! st room-id (assoc completed :run/trigger (random-uuid))))))))
