(ns dvergr.adapters.discourse-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as discourse]
            [dvergr.discourse-support :as t]
            [dvergr.room :as room]
            [dvergr.test-support :as base]
            [dvergr.world :as world]))

(deftest d1-commit-ambiguity-and-no-publication
  "D1: precommit failure offers nothing; lost commit acknowledgement retains exact history and the durable surface makes no mailbox offers."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) original dvergr.store/work!
            before (t/message) ambiguous (t/message) publication (t/message)
            offers (atom 0) post org.replikativ.spindel.spin.sync/post!]
        (with-redefs [org.replikativ.spindel.spin.sync/post! (fn [mailbox value] (swap! offers inc) (post mailbox value))]
          (with-redefs [dvergr.store/work! (fn [& args]
                                             (if (= :post (:command (last args)))
                                               (throw (ex-info "before commit" {:dvergr/error :storage-failure}))
                                               (apply original args)))]
            (is (= :storage-failure (base/error-code #(discourse/post! i v a before)))))
          (is (zero? @offers))
          (with-redefs [dvergr.store/work! (fn [& args]
                                             (let [result (apply original args)]
                                               (when (= :post (:command (last args)))
                                                 (throw (ex-info "lost acknowledgement" {:dvergr/error :storage-failure})))
                                               result))]
            (is (= :storage-failure (base/error-code #(discourse/post! i v a ambiguous)))))
          (is (zero? @offers))
          (is (= :existing (:persistence (discourse/post! i v a ambiguous))))
          (is (zero? @offers)))
        (let [result (with-redefs [org.replikativ.spindel.spin.sync/post! (fn [& _] (throw (Exception. "offer")))]
                       (discourse/post! i v a publication))]
          (is (= :inserted (:persistence result)))
          (is (= :not-offered (:live-publication result)))
          (is (= (:envelope result) (:envelope (discourse/post! i v a publication)))))))))

(deftest w1-w4-decision-handles-select-frozen-work-state
  "W1–W4: a logical decision handle resolves selected frozen bindings and refuses excluded and unrelated systems."
  (base/with-installation
    (fn [i]
      (let [conn (t/connection)
            root (world/create! i (base/assembly [(t/spec conn)]))
            handle (dvergr.world.systems/handle i root :messages #{:discourse/read :discourse/write})
            projection (random-uuid) decision (random-uuid) target (random-uuid)
            v {:system handle :projection/id projection}]
        (binding [org.replikativ.spindel.engine.core/*execution-context* (:context root)]
          (room/work! i v :discourse/write {:command :projection :record {:projection/id projection
                                                                          :projection/parent nil :projection/basis nil :room/id (random-uuid)}})
          (room/work! i v :discourse/write {:command :decision :record {:decision/id decision :projection/id projection :participant/id target :version 0}}))
        (let [child (base/frozen! i root) sibling (base/frozen! i root)
              excluded (base/frozen! i root {:systems #{}})
              unrelated (world/create! i (base/assembly))
              h (binding [org.replikativ.spindel.engine.core/*execution-context* (:context root)]
                  (discourse/decision-handle i v decision))]
          (is (= {:classifier :dvergr.injection/v1 :classification :shared}
                 (dvergr.world.sci/classify! {:decision h})))
          (doseq [[w n] [[root 0] [child 1] [sibling 2]]]
            (binding [org.replikativ.spindel.engine.core/*execution-context* (:context w)]
              (dotimes [version n]
                (room/work! i h :discourse/write {:command :decider :id decision :expected-version version :participant-id (random-uuid)}))
              (is (= n (:version (room/work! i h :discourse/read {:command :decision :id decision}))))))
          (doseq [[w code] [[excluded :system-unavailable] [unrelated :unrelated-lineage]]]
            (binding [org.replikativ.spindel.engine.core/*execution-context* (:context w)]
              (is (= code (base/error-code #(room/work! i h :discourse/read {:command :decision :id decision})))))))))))

(deftest d4-versioned-decider-and-closed-owner
  "D4/decision: local editor updates use expected versions; closed owners cannot admit more work."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) id (random-uuid) target (random-uuid)]
        (room/work! i v :discourse/write {:command :decision :record {:decision/id id :projection/id (:projection/id v) :participant/id target :version 0}})
        (let [h (discourse/decision-handle i v id)]
          (is (= 1 (:version (discourse/set-decider! i h a {:expected-version 0 :participant-id (random-uuid)}))))
          (is (= :version-conflict (base/error-code #(discourse/set-decider! i h a {:expected-version 0 :participant-id target})))))
        (let [closed (room/close! i r)]
          (is (identical? closed (room/close! i r)))
          (is (= {:status :closed} (t/await! closed)))
          (is (= :closed (base/error-code #(discourse/post! i v a (t/message))))))))))

(deftest w5-d5-work-merge-preserves-control-and-never-publishes
  "W5/D5: native frozen work absorption imports immutable messages, preserves control descriptors and never enters a live executor."
  (base/with-installation
    (fn [i]
      (let [conn (t/connection) root (world/create! i (base/assembly [(t/spec conn)]))
            h (dvergr.world.systems/handle i root :messages #{:discourse/read :discourse/write})
            projection (random-uuid) room-id (random-uuid)
            author {:principal/id (random-uuid) :participant/id (random-uuid)}
            v {:system h :projection/id projection}]
        (binding [org.replikativ.spindel.engine.core/*execution-context* (:context root)]
          (room/work! i v :discourse/write {:command :projection :record {:projection/id projection :room/id room-id
                                                                          :projection/parent nil :projection/basis nil}}))
        (let [before (dvergr.store/world-record (:control i) (get-in root [:descriptor :world/id]))
              child (base/frozen! i root) message (t/message)]
          (binding [org.replikativ.spindel.engine.core/*execution-context* (:context child)]
            (room/work! i v :discourse/write
                        {:command :post :id (:id message) :origin (get-in child [:descriptor :world/id])
                         :canonical {:request message :author author :room/id room-id :projection/id projection}}))
          (with-redefs [org.replikativ.spindel.spin.sync/post! (fn [& _] (throw (AssertionError. "merge published")))]
            (binding [org.replikativ.spindel.engine.core/*execution-context* (:context root)]
              (org.replikativ.spindel.yggdrasil/merge-fork! (:fork-handle child) {:sync? true})
              (swap! (:live i) dissoc (get-in child [:descriptor :world/id]))
              (is (= (:id message) (:message/id (room/work! i v :discourse/read {:command :message :id (:id message)}))))))
          (is (= before (dvergr.store/world-record (:control i) (get-in root [:descriptor :world/id])))))))))

(deftest d5-closed-work-writers-and-host-injection
  "D5/W4: every discourse writer rejects control record shapes; host capabilities and discourse writers cannot enter SCI."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) record (dvergr.store/room-record (:control i) (get-in r [:record :room/id]))]
        (doseq [command [:projection :decision]]
          (is (= :reserved-control-kind
                 (base/error-code #(room/work! i v :discourse/write
                                               {:command command :record {:ask/id (random-uuid) :ask/terminal {:status :reply}}})))))
        (doseq [value [r a {:nested [a]}]]
          (is (= :unclassified-value (base/error-code #(dvergr.world.sci/classify! value)))))
        (is (= :unsupported-sci-option
               (base/error-code #(dvergr.world.sci/validate-manifest!
                                  {:bindings {} :operations {'messages {'post {:system :messages :operation :discourse/write}}}}))))
        (is (= :room-offline
               (base/error-code #(discourse/post! i (assoc-in v [:room :record :room/discourse :system] :forged) a (t/message)))))
        (is (= :world-busy (base/error-code #(base/frozen! i (:world r)))))
        (is (= record (dvergr.store/room-record (:control i) (get-in r [:record :room/id]))))))))

(deftest w6-version-one-room-closure-remains-usable
  "W6/closure: version 1 room records remain readable and repeated room closure shares one passive observation."
  (base/with-installation
    (fn [i]
      (let [r (room/create! i {:id (random-uuid) :kind :root :assembly (base/assembly)})
            closed (room/close! i r) result (promise)]
        (closed #(deliver result %) #(deliver result %))
        (is (= {:status :closed} @result))
        (is (identical? closed (room/close! i r)))
        (is (= 1 (:schema/version (room/lookup (:control i) (get-in r [:record :room/id])))))))))

(deftest d4-w1-decision-binding-validates-projection-and-system
  "D4/W1: construction and edits reject another projection's binding and a forged venue system."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) other (discourse/branch! i v {:id (random-uuid)}) id (random-uuid)]
        (room/work! i other :discourse/write {:command :decision :record {:decision/id id :projection/id (:projection/id other)
                                                                          :participant/id (random-uuid) :version 0}})
        (is (= :operation-denied (base/error-code #(discourse/decision-handle i v id))))
        (let [h (discourse/decision-handle i other id)
              edit #(discourse/set-decider! i % a {:expected-version 0 :participant-id (random-uuid)})]
          (is (= :operation-denied (base/error-code #(edit (assoc h :projection/id (:projection/id v))))))
          (is (= 1 (:version (edit h)))))
        (is (= :operation-denied
               (base/error-code #(discourse/decision-handle i
                                                            (assoc-in other [:room :record :room/discourse :system] :wrong) id))))))))

(deftest w1-w2-durable-writers-use-caller-world
  "W1/W2: all remaining writers refuse unrelated, excluded and missing selected worlds; permitted edits stay in the selected child."
  (t/with-work
    (fn [i root v room-id]
      (let [id (random-uuid) target (random-uuid)
            _ (room/work! i v :discourse/write {:command :decision :record {:decision/id id :projection/id (:projection/id v)
                                                                            :participant/id target :version 0}})
            child (base/frozen! i root) excluded (base/frozen! i root {:systems #{}})
            unrelated (world/create! i (base/assembly))
            record {:room/id room-id :room/world (get-in root [:descriptor :world/id]) :room/kind :root :schema/version 2
                    :room/discourse {:system :messages :projection (:projection/id v) :retention :indefinite}}
            r {:installation i :world root :record record :owner {:closed (atom false)} :closure (atom nil)}
            _ (dvergr.store/put-room! (:control i) record)
            _ (swap! (:owners i) assoc (:room/world record) #{r})
            v (assoc v :room r) a (t/author i r) h (discourse/decision-handle i v id)
            edit #(discourse/set-decider! i h a {:expected-version 0 :participant-id (random-uuid)})
            writes [#(discourse/post! i v a (t/message))
                    #(discourse/branch! i v {:id (random-uuid)})
                    #(discourse/history i v {:limit 1}) edit
                    #(room/work! i v :discourse/write {:command :projection :record {}})
                    #(room/work! i v :discourse/write {:command :decision :record {}})
                    #(room/work! i v :discourse/write {:command :post})
                    #(room/work! i v :discourse/write {:command :decider :id id})]]
        (try
          (doseq [[w code] [[unrelated :unrelated-lineage] [excluded :system-unavailable] [nil :no-selected-world]]]
            (binding [org.replikativ.spindel.engine.core/*execution-context* (:context w)]
              (doseq [write writes]
                (is (contains? (if (= w excluded) #{code :effect-boundary-unavailable} #{code})
                               (base/error-code write))))))
          (binding [org.replikativ.spindel.engine.core/*execution-context* (:context child)]
            (is (= 1 (:version (edit)))))
          (is (= 0 (:version (room/work! i v :discourse/read {:command :decision :id id}))))
          ;; This fixture installed a durable-only owner after making frozen
          ;; children; it has no native topology to close.
          (finally (swap! (:owners i) dissoc (:room/world record))))))))
