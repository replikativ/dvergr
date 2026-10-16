(ns dvergr.sandbox.grant-write-test
  "What the sandbox hands out for a database attached to the room writes only
   where the room's grant on it permits writes. Every test runs the attack in
   a real SCI sandbox (`setup-agent-namespaces!` on a provisioned room's
   context, as a turn builds it) and asserts both halves: the write fails and
   the store is unchanged, and the capability next to it still works.

   Rooms: A is the room under attack. B's KB is attached to A with a `:read`
   grant, C's KB with `:read-write`, and C's data DB `c-ledger` with `:read`.
   These were open before: `dvergr.room/db`, the guarded `d/connect` and
   `dvergr.room/*room*` transacted any of them, including the messages store
   (a forged message), and `d/delete-database` deleted C's data DB."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.discourse :as discourse]
            [dvergr.room.registry :as registry]
            [dvergr.rooms :as droom]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.sandbox :as sandbox]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [kontor.governance :as governance]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(def ^:private world (atom nil))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (run! io/delete-file (reverse (file-seq (io/file path))))))

(defn- with-rooms [f]
  (let [original-home (paths/home)
        home (str (io/file (System/getProperty "java.io.tmpdir")
                           (str "dvergr-grant-write-" (random-uuid))))
        root (daemon/create-shared-context :with-git? false)]
    (try
      (paths/set-home! home)
      (sdb/reset-conn!)
      (srooms/clear-room-ctxs!)
      (let [provision #(binding [rtc/*execution-context* root]
                         (srooms/provision-room! {:slug % :name %}))
            a (provision "room-a")
            b (provision "room-b")
            c (provision "room-c")
            ledger (binding [rtc/*execution-context* (:room-ctx c)]
                     (srooms/create-room-db! (:room-id c) "c-ledger")
                     (some #(when (= "c-ledger" (get-in % [:system :system/name])) (:system %))
                           (sdb/systems-for-room (:room-id c))))]
        ;; attach as a host does: a grant in the system DB, and the system
        ;; registered into the room's composite (`hydrate-rooms!` at boot)
        (sdb/attach! (:room-id a) (:kb-id b) :read)
        (sdb/attach! (:room-id a) (:kb-id c) :read-write)
        (sdb/attach! (:room-id a) (:system/id ledger) :read)
        (binding [rtc/*execution-context* (:room-ctx a)]
          (doseq [system [{:system/type :kb :system/scope (:kb-path b)}
                          {:system/type :kb :system/scope (:kb-path c)}
                          ledger]]
            (#'srooms/register-system-into-current! system)))
        (let [ec (:room-ctx a)
              sci-ctx (sandbox/fork-for-session ec)]
          (sandbox/setup-agent-namespaces! sci-ctx ec :room-id (:room-id a))
          (reset! world {:a a :b b :c c :ec ec :sci-ctx sci-ctx :root root})
          (f)))
      (finally
        (srooms/set-grant-permits! nil)
        (when-let [{:keys [a b c]} @world]
          (doseq [{:keys [room-ctx room-id]} [a b c]]
            (binding [rtc/*execution-context* room-ctx]
              (try (some-> (srooms/room-msgs-conn room-id) governance/ungovern!)
                   (catch Throwable _)))))
        (reset! world nil)
        (srooms/release-room-stores!)
        (sdb/reset-conn!)
        (try (context/close-context! root) (catch Throwable _))
        (paths/set-home! original-home)
        (delete-tree! home)))))

(use-fixtures :once with-rooms)

(defn- ev
  "Evaluate `code` in room A's sandbox as a `clojure_eval` does: `{:ok v}` or
   `{:err message}`."
  [code]
  (let [{:keys [ec sci-ctx]} @world]
    (binding [rtc/*execution-context* ec]
      (let [r (sandbox/eval-code sci-ctx (str "(require '[datahike.api :as d] '[dvergr.room :as room]) "
                                              code))]
        (if (:success r) {:ok (:value r)} {:err (get-in r [:error :message])})))))

(defn- in-room [k f]
  (binding [rtc/*execution-context* (get-in @world [k :room-ctx])] (f)))

(defn- titles
  "The `:entity/title`s in the KB of room `k`, read on the host."
  [k]
  (in-room k #(set (dh/q '[:find [?t ...] :where [_ :entity/title ?t]]
                         @(srooms/room-kb-conn (get-in @world [k :room-id]))))))

(defn- message-contents []
  (in-room :a #(set (dh/q '[:find [?c ...] :where [_ :message/content ?c]]
                          @(srooms/room-msgs-conn (get-in @world [:a :room-id]))))))

(defn- read-only? [r] (boolean (re-find #"read-only" (str (:err r)))))

(deftest a-kb-attached-read-refuses-every-write
  (let [before (titles :b)
        attacks {:db "(d/transact (room/db \"room-b-kb\") [{:entity/title \"forged-db\"}])"
                 :db-keyword "(d/transact (room/db :room-b-kb) [{:entity/title \"forged-kw\"}])"
                 :transact! "@(d/transact! (room/db \"room-b-kb\") [{:entity/title \"forged-bang\"}])"
                 :connect "(d/transact (d/connect {:store {:id \"room-b-kb\"}}) [{:entity/title \"forged-connect\"}])"
                 :connect-path "(d/transact (d/connect {:store {:backend :file :path \"/x/room-b-kb\"}}) [{:entity/title \"forged-path\"}])"
                 :tx-map "(d/transact (room/db \"room-b-kb\") {:tx-data [{:entity/title \"forged-map\"}]})"
                 :schema "(d/transact (room/db \"room-b-kb\") [{:db/ident :evil/attr :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])"
                 :retract "(d/transact (room/db \"room-b-kb\") [[:db/retractEntity 1]])"}]
    (doseq [[attack code] attacks]
      (testing (name attack)
        (is (read-only? (ev code)) (pr-str (ev code)))))
    (testing "the handle offers no other way in"
      (doseq [code ["(swap! (room/db \"room-b-kb\") identity)"
                    "(reset! (room/db \"room-b-kb\") nil)"
                    "(d/load-entities (room/db \"room-b-kb\") [])"
                    "(.-resolver (room/db \"room-b-kb\"))"
                    "(.-write_refusal (room/db \"room-b-kb\"))"
                    "(dvergr.sandbox.ns.datahike.WorldConnection. (fn [] nil) nil)"
                    "(d/transact @(room/db \"room-b-kb\") [{:entity/title \"forged-value\"}])"
                    "(d/transact (room/kb \"room-b-kb\") [{:entity/title \"forged-kb\"}])"]]
        (is (:err (ev code)) code))
      (is (= {:ok [nil nil]} (ev "[(:store (room/db \"room-b-kb\")) (:store @(room/db \"room-b-kb\"))]"))))
    (testing "B's KB is unchanged"
      (is (= before (titles :b)))))
  (testing "and A still reads it"
    (in-room :b #(dh/transact (srooms/room-kb-conn (get-in @world [:b :room-id]))
                              [{:entity/title "b-note"}]))
    ;; B's KB is shared by the tests of this namespace: ask for this test's note
    (is (= {:ok true}
           (ev "(contains? (set (d/q '[:find [?t ...] :where [_ :entity/title ?t]] @(room/db \"room-b-kb\"))) \"b-note\")")))
    (is (= {:ok true}
           (ev "(contains? (set (d/q '[:find [?t ...] :where [_ :entity/title ?t]] @(d/connect {:store {:id \"room-b-kb\"}}))) \"b-note\")")))
    (is (= {:ok [false :read]}
           (ev "((juxt :writable? :permission) (first (filter #(= \"room-b-kb\" (:name %)) (room/databases))))")))))

(deftest the-messages-store-is-read-only
  (let [before (message-contents)]
    (doseq [code ["(d/transact room/*room* [{:message/id (random-uuid) :message/content \"forged-room\"}])"
                  "(d/transact (room/db \"room-a-msgs\") [{:message/id (random-uuid) :message/content \"forged-db\"}])"
                  "(d/transact (d/connect {:store {:id \"room-a-msgs\"}}) [{:message/id (random-uuid) :message/content \"forged-connect\"}])"
                  "@(d/transact! room/*room* [{:message/id (random-uuid) :message/content \"forged-bang\"}])"]]
      (is (read-only? (ev code)) code))
    (is (= before (message-contents)) "no message was written")
    (testing "even though the room owns it"
      (is (= {:ok [false :owner]}
             (ev "((juxt :writable? :permission) (first (filter #(= :msgs (:type %)) (room/databases))))"))))
    (testing "and it is read as before"
      (is (= {:ok (count before)}
             (ev "(count (d/q '[:find ?e :where [?e :message/content _]] @room/*room*))")))
      (is (= {:ok (count before)} (ev "(count (room/recent-messages 100))"))))))

(deftest write-granted-databases-keep-writing
  (testing "the room's own KB"
    (is (= {:ok 2} (ev "(count (:tx-data (d/transact room/*kb* [{:entity/title \"a-own\"}])))")))
    (is (contains? (titles :a) "a-own")))
  (testing "a KB attached :read-write, by name and by connect"
    (is (= {:ok 2} (ev "(count (:tx-data (d/transact (room/db \"room-c-kb\") [{:entity/title \"c-by-db\"}])))")))
    (is (= {:ok 2} (ev "(count (:tx-data (d/transact (d/connect {:store {:id \"room-c-kb\"}}) [{:entity/title \"c-by-connect\"}])))")))
    (is (= {:ok 2} (ev "(count (:tx-data (d/transact (room/kb \"room-c-kb\") [{:entity/title \"c-by-kb\"}])))")))
    (is (every? (titles :c) ["c-by-db" "c-by-connect" "c-by-kb"])))
  (testing "a data DB the room creates, and a scratch database"
    (is (= {:ok 4} (ev "(d/create-database {:store {:backend :file :id \"notes\"}})
                        (count (:tx-data (d/transact (d/connect {:store {:id \"notes\"}})
                                                     [{:db/ident :n/x :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])))")))
    (is (= {:ok 4} (ev "(d/create-database {:store {:backend :mem :id \"scratch\"}})
                        (count (:tx-data (d/transact (d/connect {:store {:backend :mem :id \"scratch\"}})
                                                     [{:db/ident :n/x :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])))")))
    (is (= {:ok true} (ev "(d/delete-database {:store {:id \"notes\"}})")) "and deletes its own")))

(deftest a-data-db-attached-read-is-neither-written-nor-deleted
  (let [c-ledger #(in-room :c (fn [] (srooms/room-db-conn (get-in @world [:c :room-id]) "c-ledger")))]
    (is (read-only? (ev "(d/transact (room/db \"c-ledger\") [{:db/ident :x/y :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])")))
    (is (= {:ok false} (ev "(d/delete-database {:store {:id \"c-ledger\"}})")))
    (is (some? (c-ledger)) "C's data DB is still there")
    (is (nil? (:x/y (dh/schema @(c-ledger)))))
    (is (= {:ok true} (ev "(d/database-exists? {:store {:id \"c-ledger\"}})")) "and A still sees it")))

(deftest gc-leaves-the-storage-of-read-grants-alone
  (let [{:keys [a b c]} @world
        sid (fn [k path] (str "room-" k "-" (.getName (io/file path))))
        b-kb (sid "kb" (:kb-path b))
        c-kb (sid "kb" (:kb-path c))
        a-kb (sid "kb" (:kb-path a))
        r (ev "(room/gc! {:dry-run? true})")]
    (is (contains? (in-room :a #(set (keys (ygg/registered-systems)))) b-kb)
        "B's KB is registered in A's composite")
    (is (not (contains? (:ok r) b-kb)) (pr-str r))
    (is (= :started (get (:ok r) a-kb)) "A's own systems are collected")
    (testing "the result is data: no computation to run later, after a grant changed"
      (is (every? #{:started} (vals (:ok r))) (pr-str r)))
    (testing "a detached system is not the room's either, though still registered"
      (is (contains? (:ok r) c-kb))
      (try
        (sdb/detach! (:room-id a) (:kb-id c))
        (is (contains? (in-room :a #(set (keys (ygg/registered-systems)))) c-kb))
        (is (not (contains? (:ok (ev "(room/gc! {:dry-run? true})")) c-kb)))
        (is (read-only? (ev "(d/transact (room/db \"room-c-kb\") [{:entity/title \"c-detached\"}])")))
        (finally (sdb/attach! (:room-id a) (:kb-id c) :read-write))))))

(deftest deleting-gc-is-off-until-enabled
  ;; datahike's gc-storage fails open (datahike#1117): off unless the daemon
  ;; enables it; a dry run deletes nothing and stays allowed
  (let [prev (srooms/storage-gc-enabled?)]
    (try
      (srooms/enable-storage-gc! false)
      (is (re-find #"Storage GC is off" (str (get-in (ev "(room/gc!)") [:ok :error]))))
      (is (re-find #"Storage GC is off" (str (get-in (ev "(room/gc! {:remove-before (java.util.Date.)})") [:ok :error]))))
      (is (map? (:ok (ev "(room/gc! {:dry-run? true})"))) "a dry run runs")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage GC is off" (srooms/gc-stores!)))
      (testing "a dry run never runs the system DB's GC (it has no dry run)"
        (with-redefs [datahike.api/gc-storage (fn [& _] (throw (ex-info "deleting GC ran" {})))]
          (is (:dry-run (:system-db (srooms/gc-stores! {:dry-run? true}))))))
      (testing "enabling is for the current home only"
        (srooms/enable-storage-gc! true)
        (is (srooms/storage-gc-enabled?))
        (let [home (dvergr.substrate.paths/home)]
          (try (dvergr.substrate.paths/set-home! (str home "-elsewhere"))
               (is (not (srooms/storage-gc-enabled?)) "another home is not enabled")
               (finally (dvergr.substrate.paths/set-home! home))))
        (srooms/enable-storage-gc! false)
        (is (not (srooms/storage-gc-enabled?))))
      (finally (srooms/enable-storage-gc! prev)))))

(deftest a-dead-gc-is-a-failure-not-zero
  (let [report #'srooms/gc-report]
    (is (:error (report nil)))
    (is (:error (report :dvergr.system.rooms/timeout)))
    (is (= {:reclaimed 3} (report 3)))
    (is (= {:reclaimed 2} (report #{:a :b})))))

(deftest a-fork-does-not-merge-into-a-database-the-room-may-no-longer-write
  ;; The fork writes C's KB while A's grant is :read-write, the grant is then
  ;; downgraded, and A's sandbox merges the fork: the merge would land the
  ;; fork's write in C's KB (confirmed with the check stubbed out).
  (let [{:keys [a c]} @world
        room (binding [rtc/*execution-context* (:root @world)]
               (registry/lookup (droom/create-room! {:slug "merger" :title "merger" :parent-id false})))
        room-uuid (:room/id (sdb/room-by-slug "merger"))
        sandbox-in (fn [ctx]
                     (let [sci-ctx (sandbox/fork-for-session ctx)]
                       (sandbox/setup-agent-namespaces! sci-ctx ctx :room-id room-uuid)
                       (fn [code]
                         (binding [rtc/*execution-context* ctx]
                           (let [r (sandbox/eval-code sci-ctx (str "(require '[datahike.api :as d] '[dvergr.room :as room]) " code))]
                             (if (:success r) {:ok (:value r)} {:err (get-in r [:error :message])}))))))
        fork-of (fn [] (binding [rtc/*execution-context* (:ctx room)]
                         (discourse/fork-room room {:isolation :ctx})))]
    (sdb/attach! room-uuid (:kb-id c) :read-write)
    (binding [rtc/*execution-context* (:ctx room)]
      (#'srooms/register-system-into-current! {:system/type :kb :system/scope (:kb-path c)}))
    (in-room :c #(dh/transact (srooms/room-kb-conn (get-in @world [:c :room-id]))
                              [{:entity/title "c-victim"}]))
    (try
      (let [in-room (sandbox-in (:ctx room))
            refused-merge (fn [code]
                            (sdb/attach! room-uuid (:kb-id c) :read-write)
                            (let [fork (fork-of)
                                  in-fork (sandbox-in (:ctx fork))]
                              (is (:ok (in-fork code)) code)
                              (sdb/attach! room-uuid (:kb-id c) :read)
                              (let [r (in-room (str "(room/merge! \"merger\" " (pr-str (:slug fork)) ")"))]
                                (is (re-find #"may not write" (str (:err r))) (pr-str r)))
                              (testing "the refused fork can still be discarded"
                                (is (= {:ok (:slug fork)}
                                       (in-room (str "(:slug (room/discard! " (pr-str (:slug fork)) "))")))))))]
        (testing "a fork that added to C's KB"
          (refused-merge "(d/transact (room/db \"room-c-kb\") [{:entity/title \"c-from-fork\"}])")
          (is (not (contains? (titles :c) "c-from-fork")) "C's KB is unchanged"))
        (testing "a fork that only retracted from C's KB"
          (refused-merge "(d/transact (room/db \"room-c-kb\") [[:db/retractEntity [:entity/title \"c-victim\"]]])")
          (is (contains? (titles :c) "c-victim") "C's KB is unchanged"))
        (testing "naming another parent, one whose grants permit the write, does not help"
          ;; the substrate merge lands in the fork's own parent, whatever room
          ;; the call names
          (let [other (binding [rtc/*execution-context* (:root @world)]
                        (registry/lookup (droom/create-room! {:slug "other-parent" :title "other" :parent-id false})))
                other-uuid (:room/id (sdb/room-by-slug "other-parent"))]
            (try
              (sdb/attach! other-uuid (:kb-id c) :read-write)
              (sdb/attach! room-uuid (:kb-id c) :read-write)
              (let [fork (fork-of)
                    in-fork (sandbox-in (:ctx fork))]
                (is (:ok (in-fork "(d/transact (room/db \"room-c-kb\") [{:entity/title \"c-via-other\"}])")))
                (sdb/attach! room-uuid (:kb-id c) :read)
                (let [r (in-room (str "(room/merge! \"other-parent\" " (pr-str (:slug fork)) ")"))]
                  (is (re-find #"merges into its own parent only" (str (:err r))) (pr-str r)))
                (let [r (in-room (str "(room/merge! \"merger\" " (pr-str (:slug fork)) ")"))]
                  (is (re-find #"may not write" (str (:err r))) (pr-str r)))
                (is (not (contains? (titles :c) "c-via-other")) "C's KB is unchanged")
                (in-room (str "(room/discard! " (pr-str (:slug fork)) ")")))
              (finally
                (binding [rtc/*execution-context* (:ctx other)]
                  (try (governance/ungovern! (:conn (:store other))) (catch Throwable _))
                  (discourse/close-room! other))))))
        (testing "a fork that writes only what the room may write merges"
          (let [fork (fork-of)
                in-fork (sandbox-in (:ctx fork))]
            (is (= {:ok 2} (in-fork "(count (:tx-data (d/transact room/*kb* [{:entity/title \"own-from-fork\"}])))")))
            (is (= {:ok :merger} (in-room (str "(:id (room/merge! \"merger\" " (pr-str (:slug fork)) "))"))))
            (is (= {:ok #{"own-from-fork"}}
                   (in-room "(set (d/q '[:find [?t ...] :where [_ :entity/title ?t]] @room/*kb*))")))))
        (testing "and so does a fork of a fork, into it: the durable room's grants govern"
          (let [fork (fork-of)
                in-fork (sandbox-in (:ctx fork))
                nested (binding [rtc/*execution-context* (:ctx fork)]
                         (discourse/fork-room fork {:isolation :ctx}))
                in-nested (sandbox-in (:ctx nested))]
            (is (= {:ok 2} (in-nested "(count (:tx-data (d/transact room/*kb* [{:entity/title \"own-from-nested\"}])))")))
            (is (= {:ok (:id fork)}
                   (in-fork (str "(:id (room/merge! " (pr-str (:slug fork)) " " (pr-str (:slug nested)) "))"))))
            (is (= {:ok true}
                   (in-fork "(contains? (set (d/q '[:find [?t ...] :where [_ :entity/title ?t]] @room/*kb*)) \"own-from-nested\")")))
            (in-room (str "(room/discard! " (pr-str (:slug fork)) ")")))))
      (finally
        (binding [rtc/*execution-context* (:ctx room)]
          (try (governance/ungovern! (:conn (:store room))) (catch Throwable _))
          (discourse/close-room! room))))))

(deftest the-host-answers-for-its-own-permissions
  ;; a host whose vocabulary has more words (simmis: :merge implies :write)
  (let [{:keys [a b]} @world]
    (try
      (srooms/set-grant-permits! (fn [permission action]
                                   (and (= :write action)
                                        (contains? #{:read-write :merge :owner} permission))))
      (sdb/attach! (:room-id a) (:kb-id b) :merge)
      (is (= {:ok [true 2]}
             (ev "[(:writable? (first (filter #(= \"room-b-kb\" (:name %)) (room/databases))))
                   (count (:tx-data (d/transact (room/db \"room-b-kb\") [{:entity/title \"b-merge\"}])))]")))
      (is (contains? (titles :b) "b-merge"))
      (testing "a handle asks at every write: a downgrade holds at once"
        (is (= {:ok true} (ev "(def held (room/db \"room-b-kb\")) true")))
        (sdb/attach! (:room-id a) (:kb-id b) :read)
        (is (read-only? (ev "(d/transact held [{:entity/title \"b-after-downgrade\"}])")))
        (is (not (contains? (titles :b) "b-after-downgrade"))))
      (testing "the messages store stays read-only whatever the host answers"
        (srooms/set-grant-permits! (constantly true))
        (is (read-only? (ev "(d/transact room/*room* [{:message/id (random-uuid) :message/content \"forged\"}])"))))
      (finally
        (srooms/set-grant-permits! nil)
        (sdb/attach! (:room-id a) (:kb-id b) :read)))))
