(ns dvergr.agent.conversation-capture-run-test
  "Provider-free real Run failure boundaries; no substituted capture reader."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.agent.run-savepoint-prefix-test :as prefix]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as agent]
            [dvergr.chat.context :as chat]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [konserve.core :as k]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.savepoint.portable :as portable]
            [org.replikativ.spindel.spin.sync :as sync]))

(deftest partial-next-step-input-does-not-replace-the-completed-prefix
  (let [phase (atom :initial) expected (atom nil) actual (atom nil)
        partial-id (atom nil)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  agent/run-agent-turn!
                  (fn [ctx opts]
                    (case [@phase (:turn-number opts)]
                      [:initial 0]
                      (do (chat/add-message! ctx {:role :assistant :content "completed boundary"}) :continue)
                      [:initial 1]
                      (do
                        (reset! expected (chat/get-messages ctx))
                        ;; A real incomplete tool step mutates working/durable
                        ;; input, then fails BEFORE its next checkpoint gap.
                        (chat/add-message! ctx {:role :assistant :content "partial uncompleted step"
                                                :tool-uses [{:tool-use/id "unfinished"
                                                             :tool-use/name "not-executed"}]})
                        (reset! partial-id (:message/id (last (chat/get-messages ctx))))
                        (throw (ex-info "stopped during an incomplete tool step" {})))
                      [:resumed 1]
                      (do (reset! actual (chat/get-messages ctx))
                          (chat/add-message! ctx {:role :assistant :content "completed after resume"})
                          :complete)
                      (throw (ex-info "Unexpected partial-step boundary" {}))))]
      (#'prefix/with-owned-room
       (fn [room conn]
         (let [handle (program/hire! room @#'prefix/team :worker {:task "partial step"})
               result @handle
               id (program/run-id handle)
               old (run/run room id)
               descriptor (:run/savepoint old)
               live (chat/load-messages conn (:run/chat-id old))]
           (is (= :failed (:run/status result)))
           (is (empty? (run/active-runs (:id room))))
           (is (= 0 (get-in (run/savepoint old) [:savepoint/payload :step])))
           (is (uuid? @partial-id))
           (is (some #(= @partial-id (:message/id %)) live))
           (is (not-any? #(= @partial-id (:message/id %)) @expected))
           ;; Also edit/delete actual original rows after the completed capture.
           (d/transact conn [{:db/id [:message/id (:message/id (first @expected))]
                              :message/content "later edited system prompt"}
                             [:db/retractEntity [:message/id (:message/id (second @expected))]]])
           (is (= descriptor (:run/savepoint (run/run room id))))
           (reset! phase :resumed)
           (let [successor (program/resume! room room id)]
             (is (= :completed (:run/status @successor)))
             (is (= @expected @actual) "Full model-input values, including IDs/order, are frozen")
             (is (not-any? #(= @partial-id (:message/id %)) @actual))
             (is (empty? (run/active-runs (:id room)))))))))))

(deftest pending-native-snapshot-is-off-drain-and-owned-through-cancellation
  (let [persist portable/persist
        entered (promise) engine-progress (promise)
        release (java.util.concurrent.CountDownLatch. 1)
        exited (java.util.concurrent.CountDownLatch. 1)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  agent/run-agent-turn!
                  (fn [ctx _] (chat/add-message! ctx {:role :assistant :content "completed"}) :continue)
                  portable/persist
                  (fn [& args]
                    (let [operation (apply persist args)]
                      (fn [resolve reject]
                        ;; Faithful native snapshot boundary: the original
                        ;; portable capture is retained, only its native extent
                        ;; is held. This is NOT a fake descriptor/ACK result.
                        (deliver entered {:in-drain? (simple/on-drain-thread?)
                                          :context ec/*execution-context*})
                        (try
                          (let [interrupted? (volatile! false)]
                            (loop []
                              (when-not (try (.await release) true
                                             (catch InterruptedException _
                                               (vreset! interrupted? true) false))
                                (recur)))
                            (when @interrupted? (.interrupt (Thread/currentThread)))
                            (operation resolve reject))
                          (finally (.countDown exited))))))]
      (#'prefix/with-owned-room
       (fn [room _]
         (let [handle (program/hire! room @#'prefix/team :worker {:task "held native capture"})
               id (program/run-id handle)]
           (try
             (let [entry (deref entered 10000 ::timeout)]
               (is (= false (:in-drain? entry)))
               (is (some? (:context entry)))
               (when-let [context (:context entry)]
                 (binding [ec/*execution-context* context]
                   (sync/spawn! (sp/spin (deliver engine-progress :ran))))))
             (is (= :ran (deref engine-progress 10000 ::timeout))
                 "The same world's engine remains available while native capture is held")
             (program/cancel! handle)
             (is (some #(= id (:run/id %)) (run/active-runs (:id room))))
             (is (not (contains? store/terminal-run-statuses (:run/status (run/run room id)))))
             (is (= 1 (.getCount exited)) "Cancellation is not a native completion ACK")
             (finally
               (.countDown release)
               ;; Real RunHandle completion includes its worker/session cleanup;
               ;; fixture release never follows only a timed diagnostic wait.
               @handle))
           (is (zero? (.getCount exited)))
           (is (= :cancelled (:run/status (run/run room id))))
           (is (nil? (:run/savepoint (run/run room id))))
           (is (empty? (run/active-runs (:id room))))))))))

(deftest invalid-actual-artifact-refuses-before-any-successor-or-wallet-claim
  ;; Deliberately retain a stopped Run's positive grant to make the preclaim
  ;; oracle meaningful. This stub is NOT a native accounting/cleanup proof;
  ;; the fixture explicitly returns the actual balance below before teardown.
  (with-redefs [providers/ensure-initialized! (constantly nil)
                program/return-unused-resources! (fn [& _] nil)
                agent/run-agent-turn!
                (fn [ctx opts]
                  (if (zero? (:turn-number opts))
                    (do (chat/add-message! ctx {:role :assistant :content "captured"}) :continue)
                    (throw (ex-info "intentional stopped Run" {}))))]
    (#'prefix/with-owned-room
     (fn [room conn]
       (let [chat-id (d/q '[:find ?id . :in $ ?slug
                            :where [?c :room/slug ?slug] [?c :chat/id ?id]]
                          @conn (store/room-id->slug (:id room)))]
         (resource/install-connection! conn (:id room) chat-id)
         (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 1000000M}})
         (let [handle (program/hire! room @#'prefix/team :worker
                                     {:task "invalid retained bytes"
                                      :resources {resource/microdollars 100000M}})
               result @handle
               id (program/run-id handle)
               old (run/run room id)
               ref (:run/conversation-ref old)
               wallet (resource/run-balance room id)
               room-balance (resource/balance room)
               calls (atom {})
               entered! (fn [boundary]
                          (fn [& _] (swap! calls update boundary (fnil inc 0))
                            (throw (ex-info "Forbidden pre-validation claim" {:boundary boundary}))))]
           (try
             (is (= :failed (:run/status result)))
             (is (uuid? ref))
             (is (pos? (get wallet resource/microdollars 0M)))
           ;; Real same-store corruption, not a mocked loader exception.
             (k/bassoc (:store @conn) ref (.getBytes "{}" "UTF-8") {:sync? true})
             (let [before (set (d/datoms @conn :eavt))]
               (with-redefs [run/reserve-resume! (entered! :reservation)
                             world/open! (entered! :world)
                             resource/run-wallet-balance (entered! :wallet-read)
                             resource/allocate-run! (entered! :allocation)]
                 (is (thrown-with-msg? Exception #"identity mismatch"
                                       (program/resume! room room id))))
               (is (empty? @calls))
               (is (= before (set (d/datoms @conn :eavt))))
               (is (= old (run/run room id)))
               (is (= wallet (resource/run-balance room id)))
               (is (= room-balance (resource/balance room)))
               (is (nil? (:run/resumed-by (run/run room id))))
               (is (empty? (run/active-runs (:id room)))))
             (finally
               (let [remaining (into {} (filter (fn [[_ amount]] (pos? amount)))
                                     (resource/run-balance room id))]
                 (when (seq remaining)
                   (resource/return! room id nil remaining))
                 (is (empty? (resource/run-balance room id))
                     "The deliberately retained fixture grant is returned before deleting its store"))))))))))
