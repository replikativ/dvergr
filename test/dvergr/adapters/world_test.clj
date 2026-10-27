(ns dvergr.adapters.world-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.test-support :as t]
            [dvergr.world :as world]
            [dvergr.world.systems :as systems]
            [geschichte.repo :as repo]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.sci.world :as interpreter]
            [org.replikativ.spindel.yggdrasil :as y]
            [sci.fork :as fork]
            [yggdrasil.adapters.datahike :as dh]
            [yggdrasil.adapters.geschichte :as git]
            [yggdrasil.protocols :as p]))

(deftest w1a-selected-sci-contexts
  "W1a: one stable component reference resolves to three distinct SCI contexts."
  (t/with-installation
    (fn [i]
      (let [parent (world/create! i (t/assembly))
            child (t/frozen! i parent)
            sibling (t/frozen! i parent)
            ref (:sci-ref parent)
            contexts (mapv #(interpreter/context-in (:context %) ref) [parent child sibling])]
        (is (= ref (:sci-ref child) (:sci-ref sibling)))
        (doseq [[a b] [[0 1] [0 2] [1 2]]]
          (is (not (identical? (nth contexts a) (nth contexts b)))))))))

(deftest w1-datahike-bases-and-callable-aliases
  "W1/W4: pinned adapter bases remain resolvable; immutable handle/callable aliases select distinct connections."
  (t/with-installation
    (fn [i]
      (let [conn (t/work-connection)
            _ (d/transact conn [[:db/add 100 :item/value "basis"]])
            root (world/create! i (t/work-assembly [(t/system-spec :work conn)]))
            basis (get-in (world/descriptor i root) [:assembly/components :work :basis])
            h (systems/handle i root :work #{:datahike/pull :datahike/transact})
            _ (d/transact conn [[:db/add 100 :item/value "later"]])
            child (t/frozen! i root {:snapshots {:work basis}})
            root-system (binding [ec/*execution-context* (:context root)] (systems/resolve! i h :datahike/pull))
            child-system (binding [ec/*execution-context* (:context child)] (systems/resolve! i h :datahike/pull))]
        (is (identical? h (fork/fork-value h)))
        (is (= :datahike (p/system-type child-system)))
        (is (not (identical? (:conn root-system) (:conn child-system))))
        (is (= "basis" (:item/value (d/pull (p/as-of root-system basis) [:item/value] 100))))
        (is (= "basis" (:item/value (t/evaluate i child "(work/read {:selector [:item/value] :eid 100})"))))
        (is (= "later" (:item/value (t/evaluate i root "(work/read {:selector [:item/value] :eid 100})"))))
        (binding [ec/*execution-context* (:context child)]
          (systems/invoke! i h :datahike/transact [[:db/add 100 :item/value "child"]]))
        (d/transact conn [[:db/add 100 :item/value "parent"]])
        (is (= "child" (:item/value (t/evaluate i child "(work/read {:selector [:item/value] :eid 100})"))))
        (is (= "parent" (:item/value (d/pull @conn [:item/value] 100))))
        (let [calls (atom 0) original y/system]
          (binding [ec/*execution-context* (:context root)]
            (with-redefs [y/system (fn [id] (swap! calls inc) (original id))]
              (dotimes [_ 3] (systems/invoke! i h :datahike/pull {:selector [:item/value] :eid 100}))))
          (is (= 3 @calls)))))))

(deftest w2a-geschichte-native-workspace
  "W2a: admitted Geschichte forks have distinct native workspaces and honest nil tiers."
  (t/with-installation
    (fn [i]
      (let [spec (t/geschichte-spec :files)
            _ (repo/write! (:connection spec) "dirty.txt" (.getBytes "parent" "UTF-8"))
            root (world/create! i (t/assembly [spec]))
            child (t/frozen! i root)
            selected #(binding [ec/*execution-context* (:context %)] (y/system :files))
            a (selected root) b (selected child)]
        (is (not= (git/workspace-id a) (git/workspace-id b)))
        (is (not (identical? (git/connection a) (git/connection b))))
        (is (nil? (get-in (world/descriptor i child) [:assembly/components :files :tier])))))))

(deftest w5-work-merge-cannot-change-control
  "W5: work merge, including control-like entities, leaves the physically separate control snapshot unchanged."
  (t/with-installation
    (fn [i]
      (let [conn (t/work-connection)
            _ (d/transact conn [{:db/ident :world/id :db/valueType :db.type/keyword
                                 :db/cardinality :db.cardinality/one}
                                {:db/id 100 :control/payload "before"}])
            root (world/create! i (t/assembly [(t/system-spec :work conn)]))
            record (world/descriptor i root)
            _ (store/put-room! (:control i) {:room/id (random-uuid) :room/world (:world/id record)
                                             :room/kind :root :schema/version 1})
            child (t/frozen! i root)
            control-conn (d/connect (:config @(.-conn ^dvergr.store.ControlStore (:control i))))]
        (try
          (let [before @control-conn
                snapshot (p/snapshot-id (dh/create control-conn))
                child-conn (binding [ec/*execution-context* (:context child)] (:conn (y/system :work)))]
            (d/transact child-conn [[:db/add 100 :control/payload "overwritten"]
                                    [:db/add 101 :world/id (:world/id record)]
                                    [:db/add 101 :control/payload "counterfeit"]
                                    [:db/retract 100 :control/payload "overwritten"]])
            (binding [ec/*execution-context* (:context root)]
              (y/merge-fork! (:fork-handle child) {:sync? true}))
            (swap! (:live i) dissoc (get-in child [:descriptor :world/id]))
            (is (= snapshot (p/snapshot-id (dh/create control-conn))))
            (is (= before @control-conn))
            (is (= record (store/world-record (:control i) (:world/id record))))
            (is (= "counterfeit" (:control/payload (d/pull @conn [:control/payload] 101)))))
          (finally (d/release control-conn)))))))

(deftest w5-control-store-aliases-refuse
  "W5: actual control connection, alternate lease, branch and canonical file aliases refuse registration."
  (let [config (t/control-config)
        control (store/open! config)
        i (world/installation control)
        conn (d/connect (assoc (:database config) :schema-flexibility :write))]
    (try
      (d/branch! conn :db :alias)
      (let [branch (d/connect (assoc (:config @conn) :branch :alias))]
        (try
          (doseq [alias [(.-conn ^dvergr.store.ControlStore control) conn branch]]
            (is (= :control-store-alias
                   (t/error-code #(world/create! i (t/assembly [(t/system-spec :ledger alias)]))))))
          (finally (d/release branch))))
      (let [id (random-uuid)]
        (is (= (store/storage-identity {:store {:backend :file :id id :path "/tmp/a/../control"}})
               (store/storage-identity {:store {:backend :file :id id :path "/tmp/control"}}))))
      (is (empty? @(:live i)))
      (finally (d/release conn) (store/close! control)))))

(deftest w6-control-reopen-and-role-gates
  "W6: control reopen retains installation and refuses changed identities, role and schema version."
  (let [config (t/control-config)
        control (store/open! config)
        i (world/installation control)
        root (world/create! i (t/assembly))
        record (world/descriptor i root)]
    (world/close! i root)
    (store/close! control)
    (is (= :store-closed (t/error-code #(store/world-record control (:world/id record)))))
    (let [reopened (store/open! config)]
      (try
        (is (= record (store/world-record reopened (:world/id record))))
        (is (= (:installation-id config) (:installation/id (store/info reopened))))
        (finally (store/close! reopened))))
    (is (= :identity-conflict (t/error-code #(store/open! (assoc config :installation-id (random-uuid))))))
    (doseq [[key changed code] [[:store/role :work :wrong-store-role]
                                [:schema/version 2 :schema-version]]]
      (let [conn (d/connect (:database config))
            info {:installation/id (:installation-id config) :control-store/id (:control-store-id config)
                  :schema/version 1 :store/role :control}]
        (d/transact conn [{:control/key "installation/current" :control/payload (pr-str (assoc info key changed))}])
        (d/release conn)
        (is (= code (t/error-code #(store/open! config))))))))
