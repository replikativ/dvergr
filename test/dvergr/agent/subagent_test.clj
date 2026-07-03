(ns dvergr.agent.subagent-test
  "Suite guard for the subagent delegation arc (`dvergr.agent.subagent/hire!`):
   fork → host → delegate → merge/discard, CRDT sharing through the `:ctx`
   fork, lifecycle tracking, depth cap, and error-safe discard. All scripted
   workers — no LLM, no network. (The real-LLM path shares every seam except
   run-agent-turn! itself and is exercised by the live harness.)"
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.agent.subagent :as sub]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.convergent.gset :as g]))

(defn- scripted
  "Worker that replies `content` (and optionally runs `effect!` first)."
  [id content & [effect!]]
  (d/participant
   {:id id
    :on-message (fn [_p m]
                  (sp/spin
                   (when effect! (effect!))
                   {:to (:from m) :content content}))}))

(defn- run-hire
  "Run hire! on a fresh room, deref with a watchdog so a regression can never
   hang the suite. Returns the result map (or ::hung)."
  [opts & {:keys [room-id] :or {room-id (keyword (str "sub-t-" (rand-int 1000000)))}}]
  (let [room (d/room room-id)
        f    (future
               (binding [ec/*execution-context* (:ctx room)]
                 @(sub/hire! room opts)))]
    {:room room :result (deref f 30000 ::hung)}))

(deftest hire-merges-reply
  (testing "basic delegation: fork, ask, reply, merge"
    (let [{:keys [result]} (run-hire {:goal "task" :worker (scripted :w "did it")
                                      :timeout-ms 10000})]
      (is (= :merged (:status result)))
      (is (= "did it" (:result result)))
      (is (uuid? (:subagent-id result))))))

(deftest hire-discards-on-reject
  (testing "accept-fn false → fork discarded, status :discarded"
    (let [{:keys [result]} (run-hire {:goal "task" :worker (scripted :w "junk")
                                      :accept-fn (constantly false)
                                      :timeout-ms 10000})]
      (is (= :discarded (:status result)))
      (is (= "junk" (:result result))))))

(deftest hire-times-out-without-reply
  (testing "a silent worker → :timeout, fork discarded, nil result"
    (let [silent (d/participant {:id :mute :on-message (fn [_p _m] (sp/spin nil))})
          {:keys [result]} (run-hire {:goal "task" :worker silent :timeout-ms 1500})]
      (is (= :timeout (:status result)))
      (is (nil? (:result result))))))

(deftest hire-shares-crdt-through-ctx-fork
  (testing "a worker's G-Set write inside the :ctx fork merges back to the parent"
    (let [room  (d/room (keyword (str "sub-crdt-" (rand-int 1000000))))
          kbref (binding [ec/*execution-context* (:ctx room)]
                  (ygg/register!
                   (-> (g/gset "kb" {:store-config {:backend :memory :id (random-uuid)}}
                               {:sync? true})
                       (g/conj :seed))))
          worker (scripted :w "done"
                           #(swap! (ygg/system-signal "kb")
                                   (fn [s] (g/conj s :finding))))
          f (future (binding [ec/*execution-context* (:ctx room)]
                      @(sub/hire! room {:goal "audit" :worker worker :timeout-ms 10000})))
          result (deref f 30000 ::hung)]
      (is (= :merged (:status result)))
      (is (= #{:seed :finding}
             (binding [ec/*execution-context* (:ctx room)]
               (g/elements @kbref)))
          "the fork-branched CRDT write must fold back on merge"))))

(deftest hire-tracks-lifecycle
  (testing "durable :dvergr/subagent events: :running then the terminal status;
            pending drains to zero"
    (let [{:keys [room result]} (run-hire {:goal "t" :worker (scripted :w "ok")
                                           :timeout-ms 10000})]
      (is (= :merged (:status result)))
      (Thread/sleep 400)                     ; the room log is eventually consistent
      (let [evs (->> (d/log room)
                     (filter #(= :dvergr/subagent (:type %)))
                     (mapv :status))]
        (is (= [:running :merged] evs))
        (is (empty? (binding [ec/*execution-context* (:ctx room)]
                      (sub/pending room))))))))

(deftest hire-refuses-past-max-depth
  (testing "a room whose meta carries :subagent-depth >= max-depth refuses"
    (let [room (d/room (keyword (str "sub-deep-" (rand-int 1000000))))]
      (swap! (:meta room) assoc :subagent-depth sub/max-depth)
      (let [f (future (binding [ec/*execution-context* (:ctx room)]
                        @(sub/hire! room {:goal "t" :worker (scripted :w "x")})))
            result (deref f 15000 ::hung)]
        (is (= :refused (:status result)))))))

(deftest hire-stamps-depth-into-fork
  (testing "the fork's meta carries the incremented depth (nested hires see it)"
    (let [depth-seen (atom nil)
          ;; the worker records ITS room's depth — the participant receives the
          ;; fork room as (:room p) at join
          worker (d/participant
                  {:id :w
                   :on-message (fn [p m]
                                 (sp/spin
                                  (reset! depth-seen
                                          (some-> (:room p) :meta deref :subagent-depth))
                                  {:to (:from m) :content "ok"}))})
          {:keys [result]} (run-hire {:goal "t" :worker worker :timeout-ms 10000})]
      (is (= :merged (:status result)))
      (is (= 1 @depth-seen)))))

(deftest hire-discards-fork-on-error
  (testing "a throw inside delegation yields :error and never merges"
    (let [boom (d/participant {:id :boom
                               :on-message (fn [_p _m]
                                             (throw (ex-info "worker exploded" {})))})
          {:keys [result]} (run-hire {:goal "t" :worker boom :timeout-ms 3000})]
      ;; participant-spin isolates on-message errors (logs + nil reply), so this
      ;; surfaces as :timeout, NOT a merge of partial state — assert exactly that.
      (is (contains? #{:timeout :error} (:status result)))
      (is (not= :merged (:status result))))))
