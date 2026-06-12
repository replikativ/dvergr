(ns dvergr.discourse.generation
  "GenerationHandle — the F-side primitive that bridges any participant's
   `decide` step into spindel's reactive layer.

   A `GenerationHandle` exposes the deciding process as a small reactive
   subsystem rather than a single return value:

     :token-source  — PAsyncSeq of partial-output deltas (or nil)
     :tool-calls    — mailbox/aseq of tool-call requests + results (or nil)
     :done          — Deferred resolving to the final result (or error map)
     :cancel!       — 0-arg fn to abort the generation early

   The G-coalgebra side (participant's spin-race body) awaits :done while
   racing it against budget-threshold, cancel-signal, and inbox-interrupt
   arms. Adapters wrap different F-side shapes:

     sync-handle      — F runs inline in the spin body (scripted)
     future-handle    — F runs on a Clojure future (blocking LLM call)
     external-handle  — F is fed in by external post! (human-in-loop)
     streaming-handle — F streams deltas via gen-aseq (real SSE pump)

   The bialgebra distributive law `λ : F G ⇒ G F` is named here: each
   adapter is a particular λ that fits its F-side. Swap adapters to
   change F's shape without touching the agent's G-side spin."
  (:refer-clojure :exclude [await])
  (:require [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.await :refer [await]]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Record
;; ============================================================================

(defrecord GenerationHandle
           [;; PAsyncSeq of partial output deltas; nil if non-streaming.
            token-source
   ;; Mailbox / aseq of tool-call requests + results; nil if no tool use.
            tool-calls
   ;; spindel Deferred — delivered with final result or error map.
            done
   ;; (fn [] -> nil) — best-effort abort. May be no-op for sync adapters.
            cancel!])

(defn error-result?
  "True if a handle's :done delivered an error rather than a normal result."
  [v]
  (and (map? v) (contains? v ::error)))

;; ============================================================================
;; Adapters
;; ============================================================================

(defn sync-handle
  "Run `f` synchronously inside a spin spawned on `ctx`. Result is
   delivered to :done. No streaming; :token-source and :tool-calls are
   nil. :cancel! is a no-op (cannot interrupt a synchronous body).

   Use for scripted bots, mocks, fast non-blocking generation."
  [ctx f]
  (let [done (binding [ec/*execution-context* ctx]
               (sync/create-deferred ctx))]
    (binding [ec/*execution-context* ctx]
      (sync/spawn!
       (spin
        (try
          (sync/deliver! done (f))
          (catch Throwable t
            (tel/log! {:level :error :id ::sync-handle-error
                       :data {:err (.getMessage t)}})
            (sync/deliver! done {::error (.getMessage t)}))))))
    (->GenerationHandle nil nil done (constantly nil))))

(defn future-handle
  "Run `f` on a Clojure future (a separate thread). Result is bridged
   into spindel via :done. :cancel! cancels the underlying future
   (best-effort — interrupts the thread if it's blocked in I/O).

   This is the current llm-agent shape: blocking LLM call kept off the
   spin executor so the participant-spin stays reactive."
  [ctx f]
  (let [done   (binding [ec/*execution-context* ctx]
                 (sync/create-deferred ctx))
        fut-a  (atom nil)
        cancel (fn [] (when-let [f @fut-a] (future-cancel f)) nil)]
    (reset! fut-a
            (future
              (binding [ec/*execution-context* ctx]
                (try
                  (sync/deliver! done (f))
                  (catch Throwable t
                    (tel/log! {:level :warn :id ::future-handle-error
                               :data {:err (.getMessage t)}})
                    (sync/deliver! done {::error (.getMessage t)}))))))
    (->GenerationHandle nil nil done cancel)))

(defn external-handle
  "F is fed in by external post! to `done-deferred`. Use when the
   decider is out-of-band — a human typing into the UI which posts back,
   a slow async LLM that emits via callback, an oracle bot in another
   process.

   Caller is responsible for `(sync/deliver! done-deferred result)`.
   :cancel! is provided by the caller."
  [done-deferred {:keys [cancel! token-source tool-calls]
                  :or {cancel! (constantly nil)}}]
  (->GenerationHandle token-source tool-calls done-deferred cancel!))

(defn streaming-handle
  "F streams deltas via the supplied PAsyncSeq `token-source`. Result is
   the accumulator the streaming pump builds; delivered to :done when
   the source exhausts.

   `accumulate-fn` is (fn [acc delta] -> new-acc); `acc0` is the initial
   accumulator. The pump spawns a spin that anexts each delta, applies
   accumulate-fn, and finally delivers acc to :done."
  [ctx token-source accumulate-fn acc0]
  (let [done   (binding [ec/*execution-context* ctx]
                 (sync/create-deferred ctx))
        cancelled? (atom false)
        cancel (fn [] (reset! cancelled? true) nil)]
    (binding [ec/*execution-context* ctx]
      (sync/spawn!
       (spin
        (try
          (loop [s token-source acc acc0]
            (if @cancelled?
              (sync/deliver! done {::cancelled true ::partial acc})
              (if-let [r (await (aseq/anext s))]
                (let [[delta rest-s] r]
                  (recur rest-s (accumulate-fn acc delta)))
                (sync/deliver! done acc))))
          (catch Throwable t
            (tel/log! {:level :warn :id ::streaming-handle-error
                       :data {:err (.getMessage t)}})
            (sync/deliver! done {::error (.getMessage t)}))))))
    (->GenerationHandle token-source nil done cancel)))

;; ============================================================================
;; Combinators
;; ============================================================================

(defn race-handles
  "Combine N handles into one whose :done resolves with the first arrival.
   The losing handles are cancelled.

   :cancel! cancels all underlying handles."
  [ctx & handles]
  (let [done (binding [ec/*execution-context* ctx]
               (sync/create-deferred ctx))
        winner? (atom false)]
    (binding [ec/*execution-context* ctx]
      (doseq [h handles]
        (sync/spawn!
         (spin
          (let [v (await (:done h))]
            (when (compare-and-set! winner? false true)
              (sync/deliver! done v)
                ;; Cancel the losers.
              (doseq [loser handles
                      :when (not (identical? loser h))]
                ((:cancel! loser)))))))))
    (->GenerationHandle nil nil done
                        (fn [] (doseq [h handles] ((:cancel! h)))))))

(defn fallback-handle
  "Try `primary`; if its :done resolves with an error, run `recover-fn`
   to construct a second handle and use its :done."
  [ctx primary recover-fn]
  (let [done (binding [ec/*execution-context* ctx]
               (sync/create-deferred ctx))]
    (binding [ec/*execution-context* ctx]
      (sync/spawn!
       (spin
        (let [v (await (:done primary))]
          (if (error-result? v)
            (let [secondary (recover-fn v)
                  v2 (await (:done secondary))]
              (sync/deliver! done v2))
            (sync/deliver! done v))))))
    (->GenerationHandle nil nil done
                        (fn [] ((:cancel! primary))))))
