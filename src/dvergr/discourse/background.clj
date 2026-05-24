(ns dvergr.discourse.background
  "Background tasks + notification envelopes for `dvergr.discourse`.

   Pattern: the caller hands off a long-running unit of work (a goal for
   some agent) to fire-and-forget, and the result lands as a *notification
   message* in some recipient's inbox — typically a human-participant
   that renders it however the embedding app likes (toast, badge, push
   notification, etc.). Mirrors Claude Code's background-mode +
   notifications, opencode's async tasks, and the general
   'subagent-with-callback' pattern.

   Notifications are just `dvergr.discourse` Messages with conventional
   `:metadata` keys:

     {:notification/type     :task-complete | :task-failed | :progress
      :notification/agent    <agent-id>          ;; who did the work
      :notification/task     <original-task-id>  ;; correlation id
      :notification/elapsed  <ms>}              ;; time spent

   The recipient's `on-receive` (typically `human-participant`'s
   pluggable handler) can dispatch on `:notification/type` to render
   differently than a regular reply.

   Background tasks run on the shared room execution context — no
   separate executor — so they share the spindel VT pool with the rest
   of the room's spins. Spawn keep-alive (spindel ≥ 0.1.11) means the
   spawned Spin survives GC even when nothing in user code holds a
   reference."
  (:require [dvergr.discourse :as d]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]))

;; ===========================================================================
;; Notification envelopes
;; ===========================================================================

(defn notification
  "Build a notification message addressed to `to-id`, from `from-id`,
   carrying `content` and the conventional :notification/* metadata.
   See ns docstring for the metadata vocabulary."
  [{:keys [from to content type agent task elapsed extra]
    :or {type :task-complete}}]
  (d/message from to content nil
             (merge {:notification/type    type
                     :notification/agent   agent
                     :notification/task    task
                     :notification/elapsed elapsed}
                    extra)))

(defn notification?
  "True if `msg` is a notification envelope (has :notification/type in its
   :metadata)."
  [msg]
  (some? (:notification/type (:metadata msg))))

;; ===========================================================================
;; spawn-task!
;; ===========================================================================

(defn spawn-task!
  "Fire-and-forget background task: spawn a spin that asks `agent-id` to
   do `task-msg`, then posts a notification to `notify-id` when done.

   The agent must already be joined to `room` (typically via
   `dvergr.discourse.llm/llm-agent` or `dvergr.personas/*`).

   Required keys:
     :room      — dvergr.discourse Room
     :agent     — id of the agent that will do the work (already joined)
     :task      — message-spec for d/ask: at minimum `{:content '…'}`
     :notify    — participant id to receive the notification (a human
                  via `dvergr.discourse.human` is the canonical choice;
                  any joined participant works)

   Optional keys:
     :from         — :from id on the goal message sent to the agent.
                     Default `:background`. Mostly informational.
     :timeout-ms   — give up if the agent doesn't reply in this window.
                     Posts a :task-failed notification with content
                     '[timed out]'. Default 300000 (5 min).
     :metadata     — extra metadata merged into the notification.

   Returns nil. The spawned spin is held alive by spindel's spawn
   keep-alive registry until the body resolves, so the task survives
   GC while suspended on the agent's reply.

   Caller-side cancellation isn't wired yet — the caller has no handle
   on the spawned spin. If you need cancellation, build it on top of
   spindel's `:cancel!` machinery or open a tracking issue."
  [{:keys [room agent task notify from timeout-ms metadata]
    :or   {from        :background
           timeout-ms  300000}}]
  {:pre [(some? room) (some? agent) (some? task) (some? notify)]}
  (sp/spawn!
    (sp/spin
      (let [task-id (random-uuid)
            start-ts (System/currentTimeMillis)
            reply (sp/await
                    (comb/timeout
                      (d/ask room agent
                             (-> task
                                 (update :metadata (fnil merge {})
                                         {:notification/task task-id
                                          :from from})))
                      timeout-ms ::timeout))
            elapsed (- (System/currentTimeMillis) start-ts)
            timed-out? (= reply ::timeout)]
        (d/post! room
          (notification
            {:from   agent
             :to     notify
             :content (cond
                        timed-out? "[timed out]"
                        :else      (:content reply))
             :type   (if timed-out? :task-failed :task-complete)
             :agent  agent
             :task   task-id
             :elapsed elapsed
             :extra  metadata}))
        nil)))
  nil)

;; ===========================================================================
;; Progress streaming
;; ===========================================================================

(defn post-progress!
  "Post a `:progress` notification to `notify-id` from inside an agent's
   on-message body. Useful for long-running tasks that want to stream
   status updates while still in flight.

       (post-progress! room {:from :coder :to :user
                             :content \"Wrote 12/40 tests\"
                             :agent :coder :task task-id})

   The recipient's on-receive sees a notification with
   :notification/type :progress and can update a live indicator
   (progress bar, ticker, etc.). The final :task-complete notification
   from `spawn-task!` follows when the agent's body resolves."
  [room {:keys [from to content agent task extra]}]
  (d/post! room
    (notification {:from    from
                   :to      to
                   :content content
                   :type    :progress
                   :agent   agent
                   :task    task
                   :extra   extra})))
