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
          ts 1787860800123
          metadata {:role :user
                    :source-user "Alice"
                    :attachment {:blob-id (random-uuid) :mime "audio/ogg"}
                    :audience #{:agent/reviewer}
                    :provenance {:mode :live :source :screen}}
          parent {:id parent-id :from :party/alice :to nil
                  :content "proposal" :ts (dec ts) :role :user
                  :metadata {:role :user :source-user "Alice"}}
          child {:id child-id :from :party/alice :to :agent/reviewer
                 :content "please review" :ts ts :in-reply-to parent-id
                 :role :user :metadata metadata}]
      (store/-store-room! st room-id {:slug (name room-id) :title "Contract"})
      ;; Imports and distributed replay may see a reply before its parent. The
      ;; stable UUID field must not require the target entity to exist yet.
      (store/-store-message! st room-id child)
      (store/-store-message! st room-id parent)
      ;; A retry carrying mutated data must not rewrite durable history.
      (store/-store-message! st room-id (assoc child :content "mutated retry"))
      (let [replayed (some #(when (= child-id (:id %)) %)
                           (store/-list-messages st room-id {}))]
        (is (= (select-keys child [:id :from :to :content :ts :in-reply-to :role])
               (select-keys replayed [:id :from :to :content :ts :in-reply-to :role])))
        (is (= metadata (:metadata replayed)))))))
