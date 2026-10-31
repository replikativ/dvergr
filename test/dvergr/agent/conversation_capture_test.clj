(ns dvergr.agent.conversation-capture-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.gc :as gc]
            [dvergr.agent.conversation-capture :as capture]
            [dvergr.artifact :as artifact]
            [dvergr.chat.compaction :as compaction]
            [dvergr.chat.schema :as schema]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as native-store]
            [dvergr.discourse :as discourse]
            [konserve.core :as k]
            [org.replikativ.spindel.engine.hash :as h]))

(defn- message [role content]
  {:message/id (random-uuid) :message/role role :message/content content
   :message/created-at (java.util.Date.) :message/compacted? false})

(defn- with-store
  ([f] (with-store {:store {:backend :memory :id (random-uuid)}
                    :keep-history? false :schema-flexibility :write} f))
  ([cfg f]
   (d/create-database cfg)
   (let [conn (d/connect cfg)]
     (schema/ensure-full-schema! conn)
     (let [s (native-store/make conn)
           room (discourse/make-room {:id (keyword (str "capture-" (random-uuid))) :store s})]
       (try (f {:room room :conn conn :store s :artifacts (:artifacts s)})
            (finally
              (discourse/close-room! room)
              (d/release conn)
              (d/delete-database cfg)))))))

(defn- running! [{:keys [room store conn]}]
  (let [now (java.util.Date.)
        run {:run/id (random-uuid) :run/kind :agent-task :run/room (:id room)
             :run/actor :worker :run/trigger (random-uuid) :run/status :running
             :run/created-at now :run/started-at now :run/updated-at now
             :run/world :capture-world :run/chat-id (random-uuid)}]
    ;; Storage-boundary fixture only: no executor/Run worker is started.
    (d/transact conn [(schema/create-chat-entity {:id (:run/chat-id run)})])
    (store/-store-run! store (:id room) run)
    run))

(defn- descriptor [run step prepared]
  (let [body {:savepoint/site :conversation/turn :savepoint/seq step
              :savepoint/payload {:run (:run/id run) :step step :task "fixture"
                                  :control-room (:run/room run)
                                  :conversation (capture/manifest prepared)}
              :savepoint/resume {:fn 'dvergr.agent.program/continue-llm-run :args [(:run/id run) step]}
              :world/state {} :world/systems {} :world/pinned {} :world/seed 1}]
    (assoc body :savepoint/id (h/content-hash body) :savepoint/address [:fixture step])))

(defn- publish! [{:keys [room store]} run expected step prepared]
  (store/-record-run-savepoint! store (:id room) (:run/id run)
                                {:world (:run/world run) :chat (:run/chat-id run)}
                                expected (descriptor run step prepared) prepared))

(deftest actual-typed-tool-conversation-and-compaction-round-trip
  (let [definition {:name "capture_nested"
                    :parameters {:type "object"
                                 :properties {:request {:type "object" :properties {:code {:type "string"}}}
                                              :items {:type "array" :items {:type "object"
                                                                            :properties {:value {:type "integer"}}}}}}}
        registry (atom {"capture_nested" definition})
        chat-id (random-uuid)
        messages (with-redefs-fn
                   {#'schema/get-tool-registry-atom (constantly registry)}
                   #(let [originals
                          [(schema/create-message-entity
                            {:chat-id chat-id :role :assistant :content "" :reasoning "retained reasoning"
                             :tool-uses [{:tool-use/id "nested-1" :tool-use/name "capture_nested"
                                          :tool-use/input {:request {:code "(+ 1 2)"}
                                                           :items [{:value 7}]}}]})
                           (assoc (schema/create-message-entity
                                   {:chat-id chat-id :role :tool-result :tool-use-id "nested-1"
                                    :content (apply str (repeat 30000 "large tool output "))})
                                  :message/turn-number 0)]]
                      (conj originals
                            (compaction/create-compaction-message
                             "summary" [] (mapv :message/id originals)))))
        ;; Exercise the actual working-vector pruning transformation, not
        ;; manually invented marker fields. The completed tool boundary stays.
        [messages recovered] (compaction/prune-tool-outputs messages :n-protected-turns 0)
        prepared (capture/prepare messages @registry)]
    (is (pos? recovered))
    (is (true? (:message/pruned? (second messages))))
    (is (= "summary" (:message/summary (last messages))))
    (is (= (mapv :message/id (subvec messages 0 2))
           (:message/compacted-message-ids (last messages))))
    (with-store
      (fn [{:keys [artifacts]}]
        (artifact/publish-value! artifacts (:value prepared)
                                 (fn [ref] (is (= ref (:ref prepared)))))
        (is (= messages (capture/restore artifacts (capture/manifest prepared))))))
    (is (thrown? Exception (capture/prepare (assoc-in messages [0 :message/tool-uses 0 :tool-use/input :db/id] 42)
                                            @registry)))
    (is (thrown? Exception (capture/prepare [(first messages)] @registry)))
    (is (not= (:ref prepared) (:ref (capture/prepare (assoc-in messages [2 :message/content] "another summary")
                                                     @registry))))))

(deftest pulled-compaction-coverage-survives-conversation-capture
  (with-store
    (fn [{:keys [conn artifacts]}]
      (let [chat-id (random-uuid)
            originals (mapv #(schema/create-message-entity
                              {:chat-id chat-id :role :user :content %})
                            ["first original" "second original"])
            ids (mapv :message/id originals)
            summary (schema/create-message-entity
                     {:chat-id chat-id :role :system :content "checkpoint" :compacted? true
                      :compacted-message-ids ids})]
        (d/transact conn [{:chat/id chat-id}])
        (d/transact conn (conj originals summary))
        ;; Pull only portable fields: Datahike-local entity references are
        ;; deliberately excluded from the conversation capture contract.
        (let [eid (d/q '[:find ?message . :in $ ?id :where [?message :message/id ?id]]
                       @conn (:message/id summary))
              pulled (d/pull @conn '[:message/id :message/role :message/content
                                     :message/created-at :message/compacted?
                                     :message/compacted-message-ids] eid)
              native-coverage (:message/compacted-message-ids pulled)]
          (is (= (set ids) (set native-coverage)))
          (doseq [coverage [native-coverage (set native-coverage)]]
            (let [messages (conj originals (assoc pulled :message/compacted-message-ids coverage))
                  prepared (capture/prepare messages)]
              (artifact/publish-value! artifacts (:value prepared)
                                       (fn [ref] (is (= ref (:ref prepared)))))
              (let [restored (capture/restore artifacts (capture/manifest prepared))]
                (is (= messages restored))
                (is (= (set ids) (set (:message/compacted-message-ids (peek restored)))))))))))))

(deftest capture-refuses-opaque-and-over-limit-values-before-encoding
  (doseq [bad [(assoc (message :user "x") :message/attachment-store-ref (random-uuid))
               (assoc (message :user "x") :unknown/blob (random-uuid))
               (assoc (message :user "x") :message/content (Object.))
               (assoc (message :user "x") :message/chat 42)
               (assoc (message :user "x") :message/reply-to {:db/id 42})
               (assoc (message :user "x") :message/reasoning {:opaque/ref 42})
               (assoc (message :user "x") :message/summary true)
               (assoc (message :system "checkpoint") :message/compacted-message-ids [])
               (assoc (message :system "checkpoint") :message/compacted-message-ids [nil])
               (assoc (message :system "checkpoint") :message/compacted-message-ids ["invented-id"])
               (let [id (random-uuid)]
                 (assoc (message :system "checkpoint") :message/compacted-message-ids [id id]))
               (assoc (message :system "checkpoint") :message/compacted-message-ids (list (random-uuid)))
               (assoc (message :user "x") :message/created-at 42)
               (assoc (message :user "x") :message/summary (keyword (apply str (repeat 400000 "k"))))
               (assoc (message :user "x") :message/tokens (bigint (.shiftLeft java.math.BigInteger/ONE 10000)))]]
    (let [calls (atom 0)]
      (with-redefs [artifact/prepare-value (fn [& _] (swap! calls inc) (throw (ex-info "must not encode" {})))]
        (is (thrown? Exception (capture/prepare [bad])))
        (is (zero? @calls)))))
  (let [one (message :user "x")]
    (is (thrown? Exception (capture/prepare (repeat one))))))

(deftest immutable-artifact-survives-live-edits-deletion-and-stale-lifecycle-writer
  (with-store
    (fn [{:keys [room conn store] :as fixture}]
      (let [run (running! fixture)
            messages [(assoc (message :system "original system") :message/chat [:chat/id (:run/chat-id run)])
                      (assoc (message :user "original user") :message/chat [:chat/id (:run/chat-id run)])
                      (assoc (message :assistant "original result") :message/chat [:chat/id (:run/chat-id run)])]
            prepared (capture/prepare messages)
            _ (d/transact conn messages)
            facts (publish! fixture run nil 0 prepared)]
        (d/transact conn [{:db/id [:message/id (:message/id (first messages))] :message/content "changed"}
                          [:db/retractEntity [:message/id (:message/id (second messages))]]])
        (is (= messages (:messages (store/-load-run-conversation store (:id room) (:run/id run)
                                                                 (:run/savepoint facts)))))
        (is (= (:ref prepared) (:run/conversation-ref facts)))
        (let [next-prepared (capture/prepare [(message :user "new checkpoint")])
              next-facts (publish! fixture run (:run/savepoint facts) 1 next-prepared)]
          (store/-store-run! store (:id room) (merge run facts {:run/reason :stale-lifecycle}))
          (is (= next-facts (select-keys (store/-load-run store (:id room) (:run/id run))
                                         [:run/savepoint :run/conversation-ref])))
          (is (thrown? Exception (publish! fixture run (:run/savepoint facts) 2 prepared)))
          (store/-store-run! store (:id room) (assoc run :run/status :failed :run/ended-at (java.util.Date.)))
          (is (thrown? Exception (publish! fixture run (:run/savepoint next-facts) 2 prepared))))))))

(deftest original-checkpoint-ack-is-required-even-when-the-write-committed
  (doseq [fault [:missing :stale :lost]]
    (with-store
      (fn [{:keys [room conn store] :as fixture}]
        (let [run (running! fixture)
              prepared (capture/prepare [(message :user "captured")])
              transact d/transact
              calls (atom 0)]
          (is (thrown? Exception
                       (with-redefs [d/transact
                                     (fn [& args]
                                       (swap! calls inc)
                                       (let [report (apply transact args)]
                                         (case fault
                                           :missing nil
                                           :stale (assoc report :db-after (:db-before report))
                                           :lost (throw (ex-info "lost checkpoint ACK" {})))))]
                         (publish! fixture run nil 0 prepared))))
          (is (= 1 @calls))
          ;; This separate durable read reports state, not publisher ownership.
          (let [stored (store/-load-run store (:id room) (:run/id run))]
            (is (= (pr-str (descriptor run 0 prepared)) (:run/savepoint stored)))
            (is (= (:ref prepared) (:run/conversation-ref stored)))))))))

(deftest bounded-read-hash-codec-and-semantic-validation-refuse-corruption
  (with-store
    (fn [{:keys [artifacts]}]
      (let [prepared (capture/prepare [(message :user "original")])
            m (capture/manifest prepared)]
        (artifact/publish-value! artifacts (:value prepared) identity)
        (is (thrown? Exception (capture/restore artifacts (assoc m :bytes (dec (:bytes m)))))))
      (let [prepared (artifact/prepare-value {:version 1 :messages [(message :user "x")]
                                              :tool-schemas {} :unrecognized true})]
        (artifact/publish-value! artifacts (:value prepared) identity)
        (is (thrown? Exception (capture/restore artifacts (capture/manifest prepared))))))))

(deftest actual-missing-and-hash-corrupt-managed-bytes-refuse
  (with-store
    (fn [{:keys [conn artifacts]}]
      (let [prepared (capture/prepare [(message :user "original managed bytes")])
            m (capture/manifest prepared)
            backend (:store @conn)]
        (is (nil? (artifact/get-bounded-value artifacts (:ref prepared) capture/max-bytes)))
        (is (thrown? Exception (capture/restore artifacts m)))
        (artifact/publish-value! artifacts (:value prepared) identity)
        (is (= (get-in prepared [:value :messages]) (capture/restore artifacts m)))
        ;; Deliberate corruption of ONE exact owned fresh-store blob. No
        ;; mocked reader/loader error: actual bytes no longer hash to its key.
        (k/bassoc backend (:ref prepared) (.getBytes "{}" "UTF-8") {:sync? true})
        (is (thrown-with-msg? Exception #"identity mismatch"
                              (capture/restore artifacts m)))))))

(deftest real-gc-retains-current-typed-root-and-reclaims-superseded-conversation
  (with-store
    ;; 1910 intentionally never flushes :memory PSS roots; its real mark
    ;; requires flushed physical index addresses. Use an exact fresh FILE
    ;; database for the actual mark/sweep, with explicitly no retained history.
    {:store {:backend :file :id (random-uuid)
             :path (str (java.nio.file.Files/createTempDirectory
                         "conversation-capture-gc-"
                         (make-array java.nio.file.attribute.FileAttribute 0)) "/db")}
     :keep-history? false :commit-graph? false :schema-flexibility :write
     :writer {:backend :self :writer-ownership :exclusive}}
    (fn [{:keys [room conn store artifacts] :as fixture}]
      (let [run (running! fixture)
            old (capture/prepare [(message :user "old capture")])
            old-facts (publish! fixture run nil 0 old)
            current (capture/prepare [(message :user "current capture")])
            facts (publish! fixture run (:run/savepoint old-facts) 1 current)]
        ;; Exact fresh store, no concurrent writer, keep-history? false. This
        ;; explicitly prunes ancestry so an old ref isn't retained by history.
        (let [remove-before (java.util.Date. (inc (System/currentTimeMillis)))
              reachable (gc/reachable-store-refs @conn remove-before {:sync? true})
              ;; Despite the public unbanged name, 1910's API directly returns
              ;; writer/gc-storage!'s ORIGINAL throwable promise. Await it before
              ;; checking bytes or allowing the exact fixture store to close.
              swept @(d/gc-storage conn remove-before {:min-age-ms 0})]
          (is (contains? reachable (:ref current)))
          (is (not (contains? reachable (:ref old))))
          (is (set? swept))
          (is (contains? swept (:ref old)))
          (is (not (contains? swept (:ref current))))
          (is (= (get-in current [:value :messages])
                 (:messages (store/-load-run-conversation store (:id room) (:run/id run)
                                                          (:run/savepoint facts)))))
          (is (nil? (artifact/get-bounded-value artifacts (:ref old) capture/max-bytes)))
          (is (= (:ref current) (:run/conversation-ref (store/-load-run store (:id room) (:run/id run))))))))))
