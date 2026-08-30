(ns dvergr.room.store.datahike-test
  "Tests for the DatahikeStore — focused on structured tool-use fidelity
   (the room store now persists + returns :message/tool-uses, closing the
   gap where the room-store path used to drop the tool activity the
   chat-ctx path kept)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.room.store :as store]
            [dvergr.room.store.contract :as contract]
            [dvergr.room.store.datahike :as dhs]))

(defn- mem-store []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      [conn (dhs/make conn)])))

(deftest message-envelope-contract
  (let [[_conn st] (mem-store)]
    (contract/assert-message-envelope! st :envelope-datahike)))

(deftest concurrent-message-writes-are-atomically-first-write-wins
  (testing "the first committed immutable envelope cannot be overwritten"
    (let [[_conn st] (mem-store)
          room-id :concurrent-envelope
          message-id (random-uuid)
          ready (java.util.concurrent.CountDownLatch. 2)
          reports (atom [])
          transact! dh/transact]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (with-redefs [dh/transact
                    (fn [conn tx-data]
                      ;; Force both callers past the old unlocked existence read
                      ;; before either transaction runs. The transaction function
                      ;; must still let exactly one immutable envelope win.
                      (.countDown ready)
                      (when-not (.await ready 10
                                        java.util.concurrent.TimeUnit/SECONDS)
                        (throw (ex-info "concurrent writers did not rendezvous" {})))
                      (let [content (get-in tx-data [0 2 :message/content])
                            result (transact! conn tx-data)]
                        ;; Record the report, not return order: a thread may be
                        ;; descheduled after commit but before this swap.
                        (swap! reports conj {:content content :report result})
                        result))]
        (let [first-write (future
                            (store/-store-message!
                             st room-id
                             {:id message-id :from :alice :content "first"}))
              second-write (future
                             (store/-store-message!
                              st room-id
                              {:id message-id :from :bob :content "second"}))]
          (is (not= ::timeout (deref first-write 10000 ::timeout)))
          (is (not= ::timeout (deref second-write 10000 ::timeout)))))
      (let [stored (first (store/-list-messages st room-id {}))
            writers (filter (comp seq :tx-data :report) @reports)]
        (is (= 1 (count (store/-list-messages st room-id {}))))
        (is (= 1 (count writers))
            "only the winning transaction emits message datoms")
        (is (= (:content (first writers)) (:content stored))
            "the losing transaction observes the winner instead of upserting")))))

(deftest run-lifecycle-contract
  (let [[_conn st] (mem-store)]
    (contract/assert-run-lifecycle! st :runs-datahike)))

(deftest attention-projection-contract
  (let [[_conn st] (mem-store)]
    (contract/assert-attention-projection! st :attention-datahike)))

(deftest concurrent-attention-identity-contract
  (let [[_conn st] (mem-store)]
    (contract/assert-concurrent-attention-identity!
     st :attention-race-datahike)))

(deftest thread-filter-bounds-the-datahike-pull
  (testing "the indexed root predicate runs before message bodies are pulled"
    (let [[_conn st] (mem-store)
          room-id :bounded-thread-query
          root-id (random-uuid)
          child-id (random-uuid)
          other-id (random-uuid)
          pulled-entity-ids (atom nil)
          original-pull-many dh/pull-many]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (doseq [message [{:id root-id :from :alice :content "root"
                        :thread-root-id root-id}
                       {:id child-id :from :bob :content "reply"
                        :in-reply-to root-id :thread-root-id root-id}
                       {:id other-id :from :alice :content "other"
                        :thread-root-id other-id}]]
        (store/-store-message! st room-id message))
      (with-redefs [dh/pull-many
                    (fn [db pattern entity-ids]
                      (reset! pulled-entity-ids (vec entity-ids))
                      (original-pull-many db pattern entity-ids))]
        (is (= #{root-id child-id}
               (set (map :id (store/-list-messages
                              st room-id {:thread-root-id root-id}))))))
      (is (= 2 (count @pulled-entity-ids))
          "the unrelated topic never crosses the pull boundary"))))

(deftest metadata-is-typed-and-queryable
  (testing "durable metadata is datoms, with UUID blobs marked as store refs"
    (let [[conn st] (mem-store)
          room-id :typed-metadata
          message-id (random-uuid)
          blob-id (random-uuid)
          object-id (random-uuid)]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (store/-store-message!
       st room-id
       {:id message-id :from :party/alice :to :agent/reviewer
        :content "review" :ts 1787860800123
        :metadata {:role :user
                   :mentions #{"reviewer"}
                   :audience #{:agent/reviewer}
                   :object {:kind :proposal :id object-id}
                   :attachment {:blob-id blob-id :mime "audio/ogg"}
                   :provenance {:mode :live :source :screen}}})
      (let [stored (dh/pull @conn
                            [:message/audience :message/mention-handles
                             :message/attachment-store-ref :message/attachment-mime
                             :message/provenance-mode :message/provenance-source
                             :message/object-kind :message/object-id]
                            [:message/id message-id])]
        (is (= #{:agent/reviewer} (set (:message/audience stored))))
        (is (= #{"reviewer"} (set (:message/mention-handles stored))))
        (is (= blob-id (:message/attachment-store-ref stored)))
        (is (= {:message/attachment-mime "audio/ogg"
                :message/provenance-mode :live
                :message/provenance-source :screen
                :message/object-kind :proposal
                :message/object-id object-id}
               (select-keys stored [:message/attachment-mime
                                    :message/provenance-mode
                                    :message/provenance-source
                                    :message/object-kind
                                    :message/object-id]))))
      (is (= message-id
             (dh/q '[:find ?message-id .
                     :in $ ?kind ?object-id
                     :where
                     [?message :message/object-kind ?kind]
                     [?message :message/object-id ?object-id]
                     [?message :message/id ?message-id]]
                   @conn :proposal object-id))
          "applications can resolve the speech act from its typed object")
      (is (nil? (dh/q '[:find ?a .
                        :where [?a :db/ident :message/metadata]]
                      @conn))
          "the opaque EDN attribute is absent from fresh schema"))))

(deftest unknown-metadata-must-extend-the-schema
  (testing "unknown extensions fail instead of silently becoming opaque data"
    (let [[_conn st] (mem-store)
          room-id :unknown-metadata]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown durable message"
           (store/-store-message!
            st room-id
            {:id (random-uuid) :from :alice :content "hi"
             :metadata {:role :user :unmodelled/value 1}}))))))

(deftest foreign-content-addressed-blob-id-round-trip
  (testing "non-UUID IDs from a separately configured blob CAS remain strings"
    (let [[conn st] (mem-store)
          room-id :foreign-blob
          message-id (random-uuid)
          blob-id "sha256:9e107d9d372bb6826bd81d3542a419d6"]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (store/-store-message!
       st room-id
       {:id message-id :from :party/alice :content "file"
        :metadata {:role :user
                   :attachment {:blob-id blob-id
                                :node-id "drive-node-1"
                                :name "proposal.pdf"
                                :size 4096}}})
      (let [stored (dh/pull @conn
                            [:message/attachment-store-ref
                             :message/attachment-blob-id]
                            [:message/id message-id])
            replayed (first (store/-list-messages st room-id {}))]
        (is (= {:message/attachment-blob-id blob-id} stored))
        (is (= {:blob-id blob-id
                :node-id "drive-node-1"
                :name "proposal.pdf"
                :size 4096}
               (get-in replayed [:metadata :attachment])))))))

(deftest notification-metadata-round-trip
  (testing "background notification correlation fields remain queryable"
    (let [[conn st] (mem-store)
          room-id :notification
          message-id (random-uuid)
          task-id (random-uuid)]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (store/-store-message!
       st room-id
       {:id message-id :from :agent/planner :to :party/alice :content "done"
        :metadata {:role :assistant
                   :from :background
                   :notification/type :task-complete
                   :notification/agent :agent/planner
                   :notification/task task-id
                   :notification/elapsed 1234}})
      (is (= {:message/context-from :background
              :message/notification-type :task-complete
              :message/notification-agent :agent/planner
              :message/notification-task task-id
              :message/notification-elapsed 1234}
             (dh/pull @conn
                      [:message/context-from :message/notification-type
                       :message/notification-agent :message/notification-task
                       :message/notification-elapsed]
                      [:message/id message-id])))
      (is (= {:role :assistant
              :source-user "planner"
              :from :background
              :notification/type :task-complete
              :notification/agent :agent/planner
              :notification/task task-id
              :notification/elapsed 1234}
             (:metadata (first (store/-list-messages st room-id {}))))))))

(deftest scheduler-metadata-round-trip
  (testing "scheduled messages retain their typed origin and correlation id"
    (let [[conn st] (mem-store)
          room-id :scheduled-message
          message-id (random-uuid)
          schedule-id (random-uuid)]
      (store/-store-room! st room-id {:slug (name room-id) :title "T"})
      (store/-store-message!
       st room-id
       {:id message-id :from :scheduler :to :agent/planner :content "run"
        :metadata {:source :scheduler :schedule-id schedule-id}})
      (is (= {:message/source :scheduler
              :message/schedule-id schedule-id}
             (dh/pull @conn [:message/source :message/schedule-id]
                      [:message/id message-id])))
      (is (= {:role :assistant
              :source-user "scheduler"
              :source :scheduler
              :schedule-id schedule-id}
             (:metadata (first (store/-list-messages st room-id {}))))))))

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
