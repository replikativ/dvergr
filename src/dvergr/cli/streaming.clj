(ns dvergr.cli.streaming
  "Streaming + telemetry wrapper for dvergr-cli's run-turn-fn.

   Two cross-cutting concerns are bridged into the room's bus:

   1. **Streaming tokens** — wraps the underlying `dvergr.chat.agent/
      run-agent-turn!` with an `:on-text` callback that posts
      `{:type :partial/token …}` messages so subscribers (TUI render
      loop, audit logger, etc.) see each chunk as it arrives.

   2. **Per-turn telemetry** — measures wall-clock elapsed and snapshots
      the chat-ctx's `:budget-signal` before/after the call to derive a
      cost-delta. Emits:

        {:type :telemetry/turn-started
         :payload {:turn-number :model :provider}}

        {:type :telemetry/turn-complete
         :payload {:turn-number :model :elapsed-ms :result
                   :cost-microdollars :tokens-by-type}}

      Bus default policy for `:telemetry` is `sliding-buffer 32` — UI
      consumers care about recent activity, not full backlog. A logging
      subscriber wanting every event can override per-subscription.

   Why a wrapper (vs. a telemere handler): keeps the data path local
   and observable. The bus is the same compositional kernel that
   handles messages, directives, and partial tokens — telemetry is
   just another tag namespace. No global telemere → bus routing
   table; no chat-ctx-id lookups; same shape as the rest of the model."
  (:require [dvergr.bus :as bus]
            [dvergr.chat.agent :as ca]
            [dvergr.chat.context :as cc]))

(defn- snapshot-budget
  "Return the current budget snapshot from a chat-ctx, or nil if absent."
  [chat-ctx]
  (try (cc/get-budget chat-ctx) (catch Throwable _ nil)))

(defn- budget-delta
  "Cost delta in microdollars between two budget snapshots, or 0."
  [before after]
  (max 0 (- (or (:used after) 0) (or (:used before) 0))))

(defn- by-type-delta
  "Per-resource-type delta between two budget snapshots."
  [before after]
  (let [b (or (:by-type before) {})
        a (or (:by-type after) {})]
    (reduce-kv (fn [m k v] (assoc m k (- v (get b k 0)))) {} a)))

(defn make-run-turn-fn
  "Build a run-turn-fn that streams partial tokens and emits per-turn
   telemetry onto `bus`.

   :bus           — the dvergr.bus.Bus to post onto
   :assistant-id  — sender id (for :from on the bus messages)
   :recipient-id  — addressee (so [:to recipient-id] subscribers wake on
                    partial-token messages too)
   :delegate      — underlying run-turn-fn (default: ca/run-agent-turn!)"
  [{:keys [bus assistant-id recipient-id delegate]
    :or   {delegate ca/run-agent-turn!}}]
  (fn run-turn [chat-ctx opts]
    (let [on-text (fn [chunk]
                    (when (and (string? chunk) (not= chunk ""))
                      (bus/post! bus {:type    :partial/token
                                      :from    assistant-id
                                      :to      recipient-id
                                      :payload chunk})))
          turn-no      (:turn-number opts)
          model        (:model opts)
          provider     (:provider opts)
          budget-before (snapshot-budget chat-ctx)
          started      (System/currentTimeMillis)]
      (bus/post! bus {:type    :telemetry/turn-started
                      :from    assistant-id
                      :payload {:turn-number turn-no
                                :model       model
                                :provider    provider
                                :started-at  started}})
      (let [result (delegate chat-ctx (assoc opts :on-text on-text))
            elapsed-ms (- (System/currentTimeMillis) started)
            budget-after (snapshot-budget chat-ctx)]
        (bus/post! bus
                   {:type    :telemetry/turn-complete
                    :from    assistant-id
                    :payload {:turn-number       turn-no
                              :model             model
                              :provider          provider
                              :elapsed-ms        elapsed-ms
                              :result            result
                              :cost-microdollars (budget-delta budget-before
                                                               budget-after)
                              :tokens-by-type    (by-type-delta budget-before
                                                                budget-after)}})
        result))))
