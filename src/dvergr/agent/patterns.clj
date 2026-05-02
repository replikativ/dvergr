(ns dvergr.agent.patterns
  "Multiagent coordination patterns.

   These patterns compose agents using spindel's FRP primitives:
   - pipeline: Sequential A -> B -> C
   - fan-out: Parallel broadcast, collect all
   - race-first: Parallel broadcast, first wins
   - debate: Adversarial back-and-forth
   - supervisor: Manager delegates to workers
   - feedback-loop: Iterative review cycle
   - request-response: RPC-style with timeout

   AsyncSeq-based patterns (functional composition):
   - pipe-stream: Transform agent stream with transducers
   - merge-streams: Interleave multiple agent outputs
   - take-n: Limit agent output to N results
   - filter-stream: Filter agent outputs by predicate"
  (:refer-clojure :exclude [await send])
  (:require [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :as sync]
            [org.replikativ.spindel.core :as comb]
            [org.replikativ.spindel.core :refer [await]]
            [org.replikativ.spindel.engine.core :as rtc]
            [is.simm.partial-cps.sequence :as aseq]
            [dvergr.agent.process :as agent]))

;; ============================================================================
;; Pipeline Pattern
;; ============================================================================

(defn pipeline
  "Connect agents in sequence: A's output feeds B's input.

   Returns a vector of connector spins (one for each connection).
   The connectors run indefinitely, forwarding messages.
   NOTE: All agents must share the same execution context (via binding).

   Usage:
     (binding [rtc/*execution-context* ctx]
       (pipeline agent-a agent-b agent-c)
       (agent/send! agent-a task))
     ;; Result flows: A -> B -> C"
  [& agents]
  (vec
    (for [[upstream downstream] (partition 2 1 agents)]
      ;; Uses current *execution-context* from binding
      (spin
        (loop []
          (let [msg (await (:outbox upstream))]
            (agent/send! downstream msg)
            (recur)))))))

(defn pipeline-once
  "Run task through pipeline, return final result.

   Unlike `pipeline`, this is a one-shot operation:
   sends task to first agent, waits for result from last.

   Usage:
     (binding [rtc/*execution-context* ctx]
       (await (pipeline-once [agent-a agent-b agent-c] task)))"
  [agents task]
  (let [first-agent (first agents)
        last-agent (last agents)]
    ;; Uses current *execution-context* from binding
    (spin
      ;; Start pipeline connectors
      (let [connectors (apply pipeline agents)]
        ;; Send task to first
        (agent/send! first-agent task)
        ;; Wait for result from last
        (let [result (await (:outbox last-agent))]
          ;; Note: connectors keep running - caller should stop agents when done
          result)))))

;; ============================================================================
;; Fan-out Pattern
;; ============================================================================

(defn fan-out
  "Send same task to all agents, collect all results.

   Waits for ALL agents to respond.
   NOTE: All agents must share the same execution context (via binding).

   Usage:
     (binding [rtc/*execution-context* ctx]
       (def agents [(agent/create-agent {:id :a}) ...])
       (await (fan-out agents task)))"
  [agents task]
  ;; Uses current *execution-context* from binding
  (spin
    ;; Send to all
    (doseq [a agents]
      (agent/send! a task))
    ;; Collect all results
    (await (comb/parallel
             (mapv (fn [a] (spin (await (:outbox a))))
                   agents)))))

(defn broadcast!
  "Send message to all agents (fire and forget)."
  [agents msg]
  (doseq [a agents]
    (agent/send! a msg))
  nil)

;; ============================================================================
;; Race Pattern
;; ============================================================================

(defn race-first
  "Send task to all agents, first to respond wins.

   Returns {:winner agent :result response :index idx}
   NOTE: All agents must share the same execution context (via binding).

   Usage:
     (binding [rtc/*execution-context* ctx]
       (await (race-first [fast-agent slow-agent] task)))"
  [agents task]
  ;; Uses current *execution-context* from binding
  (spin
    ;; Send to all
    (doseq [a agents]
      (agent/send! a task))
    ;; Race for first response
    (await (comb/race
             (map-indexed
               (fn [idx a]
                 (spin {:winner a
                        :result (await (:outbox a))
                        :index idx}))
               agents)))))

;; ============================================================================
;; Debate Pattern
;; ============================================================================

(defn debate
  "Two agents argue, judge decides.

   Each round:
   1. Agent A argues
   2. Agent B counter-argues
   3. Judge evaluates

   Continues until judge returns {:decided true} or max-rounds reached.

   NOTE: All agents must share the same execution context.

   Usage:
     (await (debate agent-a agent-b judge \"topic\" 5))"
  [agent-a agent-b judge topic max-rounds]
  ;; Uses current *execution-context* from binding
  (spin
        (loop [round 0
               history []]
          (if (>= round max-rounds)
            {:status :max-rounds
             :rounds round
             :history history}

            (do
              ;; Both argue in parallel
              (agent/send! agent-a {:role :argue
                                   :topic topic
                                   :round round
                                   :history history})
              (agent/send! agent-b {:role :counter
                                   :topic topic
                                   :round round
                                   :history history})

              (let [[arg-a arg-b] (await (comb/parallel
                                           [(spin (await (:outbox agent-a)))
                                            (spin (await (:outbox agent-b)))]))]

                ;; Judge evaluates
                (agent/send! judge {:role :judge
                                   :arguments [arg-a arg-b]
                                   :round round})

                (let [verdict (await (spin (await (:outbox judge))))]
                  (if (:decided verdict)
                    {:status :decided
                     :verdict verdict
                     :rounds (inc round)
                     :history (conj history {:a arg-a :b arg-b :verdict verdict})}
                    (recur (inc round)
                           (conj history {:a arg-a :b arg-b :verdict verdict}))))))))

;; ============================================================================
;; Supervisor Pattern
;; ============================================================================

(defn supervisor
  "Manager delegates tasks to workers.

   Manager emits commands:
   - {:action :delegate :worker-id N :task ...}
   - {:action :broadcast :msg ...}
   - {:action :collect} - gather all worker results
   - {:action :done :result ...}

   Returns spin that resolves when manager says :done.

   NOTE: All agents must share the same execution context.

   Usage:
     (await (supervisor manager-agent [worker-1 worker-2 worker-3]))"
  [manager workers]
  ;; Uses current *execution-context* from binding
  (spin
    (loop []
          (let [decision (await (:outbox manager))]
            (case (:action decision)
              :delegate
              (let [worker (nth workers (:worker-id decision))]
                (agent/send! worker (:task decision))
                (recur))

              :broadcast
              (do (doseq [w workers]
                    (agent/send! w (:msg decision)))
                  (recur))

              :collect
              (let [results (await (comb/parallel
                                     (mapv (fn [w] (spin (await (:outbox w))))
                                           workers)))]
                ;; Send collected results back to manager
                (agent/send! manager {:collected results})
                (recur))

              :done
              {:status :done
               :result (:result decision)}

              ;; Unknown action, continue
              (recur))))))))

;; ============================================================================
;; Feedback Loop Pattern
;; ============================================================================

(defn feedback-loop
  "Iterative review cycle: agent works, manager reviews.

   review-fn: (fn [result] -> {:approved true} | {:feedback \"...\"} | {:rejected \"...\"})

   Returns spin that resolves when approved, rejected, or max iterations.

   Usage:
     (await (feedback-loop agent #(review %) 5))"
  [agent review-fn max-iterations]
  ;; Uses current *execution-context* from binding
  (spin
        (loop [iteration 0]
          (if (>= iteration max-iterations)
            {:status :max-iterations
             :iterations iteration}

            ;; Wait for agent to complete
            (let [result (await (:outbox agent))
                  review (review-fn result)]
              (cond
                (:approved review)
                {:status :approved
                 :result result
                 :iterations (inc iteration)}

                (:rejected review)
                {:status :rejected
                 :reason (:rejected review)
                 :iterations (inc iteration)}

                ;; Needs more work - send feedback
                :else
                (do
                  (agent/send! agent {:feedback (:feedback review)
                                     :iteration (inc iteration)
                                     :previous-result result})
                  (recur (inc iteration))))))))

;; ============================================================================
;; Request-Response Pattern
;; ============================================================================

(defn request
  "Send request to agent, wait for single response with timeout.

   The task is sent with a :reply-to deferred. Agent should deliver to it.
   If agent just posts to outbox normally, we read from there.

   Usage:
     (await (request agent {:query \"...\"} 30000))"
  [agent task timeout-ms]
  ;; Uses current *execution-context* from binding
  (let [response-d (sync/deferred)]
    ;; Send task with reply-to
    (agent/send! agent (assoc task :reply-to response-d))
    ;; Race: response-d vs outbox vs timeout
    (comb/timeout
      (spin
        (await (comb/race
                 [(spin (await response-d))
                  (spin (await (:outbox agent)))])))
      timeout-ms
      {:status :timeout
       :agent-id (:id agent)})))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn collect-n
  "Collect N results from agent's outbox.

   NOTE: This is now available directly in agent.process as agent/collect-n"
  [agent n]
  ;; Uses current *execution-context* from binding
  (spin
    (loop [results []
           remaining n]
      (if (zero? remaining)
        results
        (let [result (await (:outbox agent))]
          (recur (conj results result) (dec remaining)))))))

(defn with-timeout
  "Add timeout to any pattern spin."
  [pattern-spin timeout-ms default-value]
  (comb/timeout pattern-spin timeout-ms default-value))

;; ============================================================================
;; AsyncSeq-Based Patterns - Functional Stream Composition
;; ============================================================================

(defn pipe-stream
  "Transform agent output stream with transducer.

   The agent's outbox is treated as an AsyncSeq. The transducer
   is applied lazily as outputs are consumed.

   Returns an AsyncSeq that pulls from agent, transforms values.

   Usage:
     (def processed-stream
       (pipe-stream agent (comp (map process) (filter valid?))))

     ;; Consume the stream
     (spin
       (loop []
         (let [[value rest-seq] (await (aseq/anext processed-stream))]
           (handle value)
           (recur))))"
  [agent xform]
  (aseq/sequence xform (:outbox agent)))

(defn take-n
  "Take first N results from agent output stream.

   Returns an awaitable (CPS function) that resolves with a vector of N results.

   Usage:
     (spin (await (take-n agent 5)))"
  [agent n]
  (aseq/into [] (take n) (:outbox agent)))

(defn filter-stream
  "Filter agent output stream by predicate.

   Returns an AsyncSeq containing only values that satisfy pred.

   Usage:
     (def filtered (filter-stream agent #(> (:score %) 0.8)))
     (await (take-n {:execution-ctx ctx :outbox filtered} 3))"
  [agent pred]
  (pipe-stream agent (filter pred)))

(defn map-stream
  "Map function over agent output stream.

   Returns an AsyncSeq with transformed values.

   Usage:
     (def scores (map-stream agent :score))
     (await (take-n {:execution-ctx ctx :outbox scores} 5))"
  [agent f]
  (pipe-stream agent (map f)))

(defn pipe-agents
  "Connect agents via stream transformation.

   Output of agent-a is transformed by xform, then fed to agent-b.
   Returns a connector spin that runs indefinitely.

   Usage:
     (pipe-agents agent-a (comp (map analyze) (filter important?)) agent-b)
     ;; Now agent-a's outputs flow through xform to agent-b"
  [agent-a xform agent-b]
  (let [transformed (pipe-stream agent-a xform)]
    ;; Uses current *execution-context* from binding
    (spin
      (loop []
        (let [[value rest-seq] (await (aseq/anext transformed))]
          (agent/send! agent-b value)
          (recur))))))

(defn aggregate
  "Collect and reduce agent output stream.

   Takes N results from agent, reduces them with f starting from init.

   Returns an awaitable (CPS function) that resolves with the reduced value.

   Usage:
     (spin (await (aggregate agent + 0 10)))  ;; Sum first 10 outputs
     (spin (await (aggregate agent conj [] 5)))  ;; Collect 5 into vector"
  [agent f init n]
  (aseq/transduce (take n) f init (:outbox agent))))

(defn batch
  "Batch agent outputs into groups of size n.

   Returns an AsyncSeq of vectors, each containing n results.

   Usage:
     (def batched (batch agent 3))
     ;; Outputs: [r1 r2 r3] [r4 r5 r6] ..."
  [agent n]
  (pipe-stream agent (partition-all n)))

(defn debounce-stream
  "Debounce agent output stream by time window.

   Takes only the first output in each time window of ms milliseconds.
   Useful for rate-limiting high-frequency agents.

   Returns an AsyncSeq with debounced values.

   Usage:
     (def debounced (debounce-stream agent 1000))  ;; Max 1 per second"
  [agent ms]
  ;; Uses current *execution-context* from binding
  ;; Create a new AsyncSeq that implements debouncing
  (reify aseq/PAsyncSeq
    (anext [_this]
      (spin
        (let [[value _] (await (aseq/anext (:outbox agent)))]
          ;; Wait for debounce period
          (await (comb/sleep ms))
          ;; Return value and this debounced stream as rest
          [value _this])))))
