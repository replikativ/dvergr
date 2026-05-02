(ns dvergr.agent.process
  "Agent process abstraction — long-lived reactive agents with mailboxes.

  Key principles:
  - Agents use dynamic binding for execution context (rtc/*execution-context*)
  - Mailboxes created in current context (not captured)
  - Context is never stored in the agent record — always comes from binding
  - Patterns work naturally via mailbox communication

  For fork-isolated task delegation, see dvergr.agent.task (ask!, spawn!).

  Usage:
    (binding [rtc/*execution-context* ctx]
      (def coder (create-agent {:id :coder
                                :provider :fireworks
                                :model \"qwen3-coder-480b\"}))

      ;; One-shot task
      @(ask coder \"Implement feature X\")

      ;; Long-lived process
      (start! coder think-fn)
      (send! coder task-1)
      (send! coder task-2))"
  (:refer-clojure :exclude [send await])
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.core :as sync]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :as comb]
            [org.replikativ.spindel.core :refer [await]]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [is.simm.partial-cps.sequence :refer [PAsyncSeq anext]]
            [dvergr.git :as git]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [datahike.api :as dh]
            [taoensso.telemere :as tel]))

;; NOTE: For fork-isolated task delegation (ask!, spawn!, merge!, discard!),
;; see dvergr.agent.task. This namespace provides the process-level
;; agent (mailbox loop, lifecycle) — task provides the task-level agent
;; (fork context, run turns, return result for merge/discard).

;; =============================================================================
;; Agent Record
;; =============================================================================

(defrecord Agent
  [id              ; keyword - unique identifier
   inbox           ; Mailbox - receives tasks/messages (in current context)
   outbox          ; Mailbox - emits results/completions (in current context)
   control         ; Mailbox - control commands (:pause, :stop, :resume)
   state-a         ; Atom - {:status :running/:paused/:stopped, :turn 0, ...}
   config          ; {:provider :model :system-prompt :tools ...}
   loop-spin-a])   ; Atom holding the running loop spin (if started)

;; NO execution-ctx field! Context comes from rtc/*execution-context* binding

;; =============================================================================
;; Creation
;; =============================================================================

(defn create-agent
  "Create an agent in current execution context.

  Mailboxes are created using current rtc/*execution-context*.
  The agent does NOT capture the context - it uses dynamic binding.

  Config options:
  - :id - Unique identifier (auto-generated if not provided)
  - :provider - LLM provider (:anthropic, :openai, :fireworks)
  - :model - Model identifier
  - :system-prompt - System prompt for the agent
  - :tools - Tools available to the agent

  Returns Agent record.

  Example:
    (binding [rtc/*execution-context* ctx]
      (def coder (create-agent
                   {:id :coder
                    :provider :fireworks
                    :model \"qwen3-coder-480b\"
                    :system-prompt \"You are an expert Clojure developer\"})))"
  [config]
  (let [agent-id (or (:id config) (keyword (gensym "agent-")))]
    ;; Mailboxes use current *execution-context* implicitly
    (->Agent
      agent-id
      (sync/mailbox)      ; inbox
      (sync/mailbox)      ; outbox
      (sync/mailbox)      ; control
      (atom {:status :created :turn 0 :created-at (System/currentTimeMillis)})
      (assoc config :id agent-id)
      (atom nil))))       ; loop-spin placeholder

;; =============================================================================
;; Communication Primitives
;; =============================================================================

(defn send!
  "Post message to agent's inbox.

  Uses current rtc/*execution-context* implicitly via mailbox."
  [agent message]
  ((:inbox agent) message))

(defn ask
  "Send task to agent and await its correlated response.

  Uses a per-request deferred (:reply-to) so the response is guaranteed
  to correspond to THIS task — immune to busy responses and other tasks'
  results that may appear on the shared outbox.

  The task is wrapped in an envelope {:__ask-payload T :reply-to D}.
  The agent loop unwraps it before passing to think-fn, and delivers
  the result to the deferred when the task completes.

  Returns a spin that resolves when the agent finishes this specific task.

  Example:
    @(ask coder \"Implement feature X\")"
  [agent task]
  (let [d (sync/deferred)]
    (send! agent {:__ask-payload task :reply-to d})
    (spin (await d))))

(defn tell!
  "Send message to agent without waiting for response (fire-and-forget).

  Returns nil."
  [agent message]
  (send! agent message)
  nil)

(defn collect-n
  "Collect N results from agent's outbox.

  Returns a spin that resolves to vector of results.

  Example:
    @(collect-n agent 3)  ; => [result1 result2 result3]"
  [agent n]
  (spin
    (loop [results []
           remaining n]
      (if (zero? remaining)
        results
        (let [result (await (:outbox agent))]
          (recur (conj results result)
                 (dec remaining)))))))

;; =============================================================================
;; Lifecycle - Long-Lived Processes
;; =============================================================================

(defn- unwrap-ask-envelope
  "If task is an ask envelope {:__ask-payload T :reply-to D}, extract the
  real task and reply-to deferred. Otherwise return [task nil]."
  [task]
  (if (and (map? task) (contains? task :__ask-payload))
    [(:__ask-payload task) (:reply-to task)]
    [task (when (map? task) (:reply-to task))]))

(defn- fire-think!
  "Fire a think-fn spin in the background, delivering result to completion mailbox.

  The think-fn spin runs concurrently — it doesn't block the agent loop.
  When it completes (or errors), the result is posted to completion-mb.

  Extracts :reply-to from ask envelopes so think-fn gets a clean task.

  Returns {:mb completion-mailbox, :reply-to deferred-or-nil}."
  [agent task think-fn execution-ctx]
  (let [[clean-task reply-to] (unwrap-ask-envelope task)
        completion-mb (sync/mailbox)
        task-spin     (think-fn agent clean-task)]
    (binding [rtc/*execution-context* execution-ctx]
      (task-spin
        (fn [result] (completion-mb result))
        (fn [err]    (completion-mb {:error (str err) :agent-id (:id agent)}))))
    {:mb completion-mb :reply-to reply-to}))

(defn start!
  "Start the agent's reactive loop.

  think-fn: (fn [agent task] -> spin) - Called for each task from inbox.
           Must return a spin that resolves to response (posted to outbox).
           The spin runs concurrently — the loop remains responsive while it runs.

  Options:
  - :interval-ms - When set, agent wakes itself via FRP sleep every N ms,
                   calling think-fn with {:type :tick}. If the agent is already
                   processing, the tick is skipped (no concurrent sweeps).
                   If nil, only wakes on inbox messages.
  - :sources     - Vector of {:name keyword :source PAsyncSeq} representing
                   external event sources (e.g. room subscriptions). Each source
                   is raced alongside inbox/ctrl/tick. When a source event wins,
                   think-fn is called with {:type :source :name name :msg event}.
                   Skipped while agent is busy (same as ticks).

  The loop (Happy Eyeballs FRP pattern):
  1. Always races 5 arms: done (or never), inbox, ctrl, tick (or never), sources (or never)
  2. On task/tick/source: fires think-fn in background, loop stays responsive
  3. While active: new inbox messages get an immediate 'busy' reply;
                   ticks and source events are skipped
  4. On :done: active task completed, post result to outbox
  5. On :pause: waits for :resume (active task continues in background)
  6. On :stop: exits

  Returns the loop spin (already started)."
  [agent think-fn & {:keys [interval-ms sources]}]
  ;; Capture execution context from caller so agent loop maintains it
  (let [execution-ctx rtc/*execution-context*]
    (swap! (:state-a agent) assoc :status :running :started-at (System/currentTimeMillis))
    (let [loop-spin
          (binding [rtc/*execution-context* execution-ctx]
            (spin
              (tel/log! {:id :agent/loop-start
                         :data {:agent-id (:id agent) :interval-ms interval-ms
                                :source-count (count sources)}}
                        "Agent loop starting")
              ;; active: nil | {:mb <completion-mailbox> :reply-to <deferred-or-nil>}
              ;; When nil = idle. The Happy Eyeballs pattern: always race all arms,
              ;; using (never) as a placeholder when an arm isn't applicable.
              (loop [active nil]
                (tel/log! {:level :trace :id :agent/loop-wait
                           :data {:agent-id (:id agent) :busy? (boolean active)}} "Agent waiting")
                (let [active-mb (:mb active)
                      ;; Always race 5 arms — (never) never wins, cleanly disabled
                      [msg-type payload]
                      (await (apply comb/race
                               (cond-> [;; Arm 1: active task completes (or never if idle)
                                        (spin [:done (await (if active-mb active-mb (comb/never)))])
                                        ;; Arm 2: new inbox message
                                        (spin [:inbox (await (:inbox agent))])
                                        ;; Arm 3: control command
                                        (spin [:ctrl (await (:control agent))])
                                        ;; Arm 4: self-tick (or never if no interval)
                                        (if interval-ms
                                          (spin [:tick (await (comb/sleep interval-ms))])
                                          (spin [:tick (await (comb/never))]))]
                                 ;; Arm 5+: external sources (one race arm per source)
                                 (seq sources)
                                 (into (mapv (fn [{:keys [name source]}]
                                               (spin [:source {:name name
                                                               :msg  (first (await (anext source)))}]))
                                            sources)))))]
                  (case msg-type
                    ;; --- Task complete: post result and become idle ---
                    :done
                    (do (tel/log! {:id :agent/task-done :data {:agent-id (:id agent)}} "Background task done")
                        ((:outbox agent) payload)
                        ;; Deliver to correlated caller if this was an ask
                        (when-let [d (:reply-to active)]
                          (sync/deliver! d payload))
                        (recur nil))

                    ;; --- Control: stop / pause / unknown ---
                    :ctrl
                    (case (:cmd payload)
                      :stop
                      (do (swap! (:state-a agent) assoc
                                 :status :stopped
                                 :stopped-at (System/currentTimeMillis))
                          {:stopped true :agent-id (:id agent)})

                      :pause
                      (do (swap! (:state-a agent) assoc :status :paused)
                          ;; Wait for resume or stop. Active task keeps running in bg.
                          (loop []
                            (let [cmd (await (:control agent))]
                              (case (:cmd cmd)
                                :resume
                                (do (swap! (:state-a agent) assoc :status :running)
                                    nil)
                                :stop
                                (do (swap! (:state-a agent) assoc
                                           :status :stopped
                                           :stopped-at (System/currentTimeMillis))
                                    {:stopped true :agent-id (:id agent)})
                                (recur))))
                          ;; Resume: continue outer loop with same active state
                          (recur active))

                      ;; Unknown control command
                      (recur active))

                    ;; --- Tick: start sweep or skip if busy ---
                    :tick
                    (if active
                      (do (tel/log! {:id :agent/tick-skipped :data {:agent-id (:id agent)}}
                                    "Tick skipped — already processing")
                          (recur active))
                      (do (tel/log! {:id :agent/tick :data {:agent-id (:id agent)}} "Agent tick")
                          (swap! (:state-a agent) update :turn inc)
                          (recur (fire-think! agent {:type :tick} think-fn execution-ctx))))

                    ;; --- Source: external event (room message, etc.) ---
                    :source
                    (if active
                      (do (tel/log! {:id :agent/source-skipped
                                     :data {:agent-id (:id agent) :source (:name payload)}}
                                    "Source event skipped — already processing")
                          (recur active))
                      (do (tel/log! {:id :agent/source
                                     :data {:agent-id (:id agent) :source (:name payload)}}
                                    "Agent got source event")
                          (swap! (:state-a agent) update :turn inc)
                          (recur (fire-think! agent {:type :source
                                                     :name (:name payload)
                                                     :msg  (:msg payload)}
                                              think-fn execution-ctx))))

                    ;; --- Inbox: start task or respond busy ---
                    :inbox
                    (if active
                      (do (tel/log! {:id :agent/busy :data {:agent-id (:id agent)}} "Agent busy, acknowledging")
                          (let [busy-msg {:status  :busy
                                          :content "I'm working on something — I'll get back to you shortly."
                                          :agent-id (:id agent)}
                                ;; If this is a correlated ask, deliver busy to caller directly
                                [_ reply-to] (unwrap-ask-envelope payload)]
                            (if reply-to
                              ;; Correlated: deliver to caller's deferred, don't pollute outbox
                              (sync/deliver! reply-to busy-msg)
                              ;; Uncorrelated send!: post to outbox (legacy behavior)
                              ((:outbox agent) busy-msg)))
                          (recur active))
                      (do (tel/log! {:id :agent/task :data {:agent-id (:id agent)}} "Agent got task")
                          (swap! (:state-a agent) update :turn inc)
                          (recur (fire-think! agent payload think-fn execution-ctx)))))))))]
      (reset! (:loop-spin-a agent) loop-spin)
      ;; Start execution (fire and forget) with context bound
      (binding [rtc/*execution-context* execution-ctx]
        (loop-spin
         (fn [result] (tel/log! {:id :agent/loop-complete :data {:agent-id (:id agent) :result result}} "Agent loop completed"))
         (fn [error]  (tel/log! {:level :error :id :agent/loop-error :error error
                                 :data {:agent-id (:id agent)}} "Agent loop error"))))
      loop-spin)))

(defn stop!
  "Stop the agent's reactive loop.

  Sends :stop command to control mailbox."
  [agent]
  ((:control agent) {:cmd :stop}))

(defn pause!
  "Pause the agent's reactive loop.

  Sends :pause command to control mailbox."
  [agent]
  ((:control agent) {:cmd :pause}))

(defn resume!
  "Resume a paused agent.

  Sends :resume command to control mailbox."
  [agent]
  ((:control agent) {:cmd :resume}))

(defn steer!
  "Inject guidance into the agent's next turn without interrupting current work.
  The message will be injected as a system message before the next LLM call.
  Use this to redirect a running agent without aborting it."
  [agent message]
  (swap! (:state-a agent) update :pending-steers (fnil conj []) message)
  nil)

(defn snapshot-turn-context
  "Create an immutable snapshot of agent configuration at turn start.
  Prevents mid-turn mutations from causing inconsistencies."
  [agent]
  (let [config (:config agent)]
    {:turn-number (:turn @(:state-a agent))
     :model (:model config)
     :provider (:provider config)
     :system-prompt (:system-prompt config)
     :timestamp (java.time.Instant/now)}))

;; =============================================================================
;; State Inspection
;; =============================================================================

(defn status
  "Get current status of agent.

  Returns: {:status :running/:paused/:stopped/:created
            :turn N
            :created-at timestamp
            :started-at timestamp (if started)
            :stopped-at timestamp (if stopped)}"
  [agent]
  @(:state-a agent))

(defn running?
  "Check if agent is running."
  [agent]
  (= :running (:status @(:state-a agent))))

(defn stopped?
  "Check if agent is stopped."
  [agent]
  (= :stopped (:status @(:state-a agent))))

(defn paused?
  "Check if agent is paused."
  [agent]
  (= :paused (:status @(:state-a agent))))

;; =============================================================================
;; Utility
;; =============================================================================

(defn create-shared-context
  "Create a shared execution context for collaborating agents.

  Agents created in the same binding share the context.

  Options:
    :with-git? - Register git system for isolated agent worktrees (default: true)
    :with-datahike? - Register Datahike system for persistent, forkable db (default: true)
    :repo-path - Path to git repo (default: current directory)
    :worktrees-dir - Where to create worktrees (default: .git-worktrees)
    :db-path - Path for Datahike file store (default: .datahike in repo-path)

  Usage:
    (def ctx (create-shared-context))
    (binding [rtc/*execution-context* ctx]
      (def agent-a (create-agent {:id :a ...}))
      (def agent-b (create-agent {:id :b ...}))
      ;; Now agent-a and agent-b can coordinate naturally"
  [& {:keys [with-git? with-datahike? repo-path worktrees-dir db-path]
      :or {with-git? true
           with-datahike? true
           repo-path (System/getProperty "user.dir")
           worktrees-dir ".git-worktrees"}}]
  (let [base-ctx (ctx/create-execution-context)
        db-store-path (or db-path (str repo-path "/.datahike"))]
    (binding [rtc/*execution-context* base-ctx]
      ;; Register git system if requested
      (when with-git?
        (try
          (let [git-sys (git/create-git-system
                         :repo-path repo-path
                         :worktrees-dir worktrees-dir)]
            (ygg/register! git-sys))
          (catch Exception e
            (tel/log! {:level :warn :id :agent/git-init-failed :data {:error (.getMessage e)}} "Could not register git system"))))

      ;; Register Datahike system if requested
      (when with-datahike?
        (try
          ;; Use a stable UUID derived from the path for persistence
          (let [db-id (java.util.UUID/nameUUIDFromBytes (.getBytes db-store-path))
                db-cfg {:store {:backend :file
                                :path db-store-path
                                :id db-id}
                        :keep-history? true
                        :schema-flexibility :write}
                ;; Create database if it doesn't exist
                _ (when-not (dh/database-exists? db-cfg)
                    (dh/create-database db-cfg))
                conn (dh/connect db-cfg)
                ;; Wrap with Yggdrasil adapter
                dh-sys (dh-adapter/create conn {:system-name "dvergr-chat-db"})]
            ;; Install full schema before registering — ensures forked DBs
            ;; can merge back without schema mismatch errors
            (try
              (require 'dvergr.chat.schema)
              ((resolve 'dvergr.chat.schema/ensure-full-schema!) conn)
              (catch Exception e
                (tel/log! {:level :warn :id :agent/schema-install-failed
                           :data {:error (.getMessage e)}} "Could not install chat schema")))
            (ygg/register! dh-sys)
            (tel/log! {:id :agent/datahike-registered :data {:path db-store-path}} "Registered Datahike system"))
          (catch Exception e
            (tel/log! {:level :warn :id :agent/datahike-init-failed :error e
                       :data {:error (.getMessage e)}} "Could not register Datahike system"))))

      base-ctx)))

(defn get-datahike-conn
  "Get the Datahike connection from the current execution context.

  Returns the raw datahike connection (atom) if a DatahikeSystem is registered,
  or nil if not available.

  Usage:
    (binding [rtc/*execution-context* ctx]
      (when-let [conn (get-datahike-conn)]
        (dh/transact conn [...])))"
  []
  (when-let [dh-sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
    (:conn dh-sys)))

;; =============================================================================
;; Documentation Examples
;; =============================================================================

(comment
  ;; Example 1: One-shot task (using process agent)
  (require '[org.replikativ.spindel.engine.core :as rtc])

  (def ctx (create-shared-context))

  (binding [rtc/*execution-context* ctx]
    (def coder (create-agent {:id :coder
                              :provider :fireworks
                              :model "qwen3-coder-480b"}))

    ;; Send task and wait for result
    (def result @(ask coder "Implement feature X"))
    (println "Result:" result))


  ;; Example 2: Long-lived agent
  (binding [rtc/*execution-context* ctx]
    (def agent (create-agent {:id :my-agent}))

    ;; Define think function
    (defn my-think-fn [agent task]
      (spin
        (println "Processing:" task)
        {:result (str "Processed: " task)}))

    ;; Start agent loop
    (start! agent my-think-fn)

    ;; Send multiple tasks
    (send! agent "Task 1")
    (send! agent "Task 2")
    (send! agent "Task 3")

    ;; Collect results
    (def results @(collect-n agent 3))
    (println "Results:" results)

    ;; Stop when done
    (stop! agent))


  ;; Example 3: Multiple agents coordinating
  (binding [rtc/*execution-context* ctx]
    (def agent-a (create-agent {:id :a}))
    (def agent-b (create-agent {:id :b}))

    ;; Agent B processes A's outputs
    (spin
      (loop []
        (let [result (await (:outbox agent-a))]
          (send! agent-b result)
          (recur))))

    ;; Send task to A
    (send! agent-a "Initial task")

    ;; Get final result from B (ask sends task and awaits correlated response)
    (def final-result @(ask agent-b "Process the chain")))


  ;; Example 4: Fork-isolated task delegation
  ;; For fork/merge with yggdrasil isolation, use dvergr.agent.task:
  ;;
  ;; (require '[dvergr.agent.task :as task]
  ;;          '[dvergr.agent.config :as agent-config])
  ;;
  ;; (def coder (agent-config/make-agent {:name "coder" :isolation :sci ...}))
  ;; (binding [rtc/*execution-context* ctx]
  ;;   (let [result @(task/ask! coder "Implement feature X")]
  ;;     (task/merge! result)))
  )
