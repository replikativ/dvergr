(ns dvergr.room.store.datahike-test
  "Tests for the DatahikeStore — focused on structured tool-use fidelity
   (the room store now persists + returns :message/tool-uses, closing the
   gap where the room-store path used to drop the tool activity the
   chat-ctx path kept)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as dhs]))

(defn- mem-store []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      [conn (dhs/make conn)])))

(deftest tool-uses-round-trip
  (testing "the room store persists and returns structured :tool-uses"
    (let [[_conn st] (mem-store)
          room-id    :tg-1]
      (store/-store-room! st room-id {:slug "tg-1" :title "T"})
      (store/-store-message! st room-id
                             {:id (random-uuid) :from :var :content "running tools"
                              :metadata {:role :tool
                                         :tool-uses [{:tool-use/id "tu1" :tool-use/name "grep"}
                                                     {:tool-use/id "tu2" :tool-use/name "read_file"}]}})
      (let [msgs (store/-list-messages st room-id {})
            m    (first msgs)]
        (is (= 1 (count msgs)))
        (is (= :tool (:role m)) "role from metadata is preserved")
        (is (= #{"grep" "read_file"}
               (set (map :tool-use/name (:tool-uses m))))
            "structured tool-uses round-trip through the store")))))

(deftest plain-message-has-no-tool-uses-key
  (testing "a message without tool activity carries no :tool-uses key"
    (let [[_conn st] (mem-store)
          room-id    :tg-2]
      (store/-store-room! st room-id {:slug "tg-2" :title "T"})
      (store/-store-message! st room-id
                             {:id (random-uuid) :from :alice :content "hi" :metadata {:role :user}})
      (let [m (first (store/-list-messages st room-id {}))]
        (is (= "hi" (:content m)))
        (is (not (contains? m :tool-uses)))))))
