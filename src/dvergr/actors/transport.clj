(ns dvergr.actors.transport
  "Transport protocol for invoking actors of any kind.

   The harness (dvergr) knows about four actor kinds:
     :agent     — LLM-driven; runs in-process as a discourse Participant
     :human     — a user; reached through room mentions / channel bridges
     :external  — wraps an out-of-process capability (MCP, HTTP, RPC)
     :service   — built-in daemon-level service

   Each kind has a different invocation mechanism. Rather than baking
   each one into `dvergr.orchestration.skills/dispatch!`, we define a small protocol
   that downstream applications can satisfy. dvergr ships built-in
   impls for `:agent` and `:human`. The `:external` and `:service`
   slots are deliberately empty so embedders (simmis, custom hosts)
   can plug in MCP clients, HTTP transports, or whatever else without
   dvergr needing to know the wire format.

   ## Adding a transport

   ```clojure
   (defrecord MyMcpTransport [http-client base-url auth]
     PActorTransport
     (invoke [_ actor task]
       ;; POST to base-url + tools/call with auth header, return result
       ...)
     (probe-skills [_ actor]
       ;; GET tools/list, mint :provides tags from each tool's name
       ...))

   (transport/register-transport! :external (->MyMcpTransport ...))
   ```

   ## Calling

   ```clojure
   (transport/dispatch conn actor {:task \"...\" :room-id ... :skill ...})
   ;; → {:actor actor :status :dispatched :task <task-or-nil>}
   ;;   or {:status :no-provider} / :unsupported / :error
   ```

   The result map mirrors `skills/dispatch!` so the two can be
   refactored into one entry point without breaking callers."
  (:require [dvergr.orchestration.tasks :as tasks]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol PActorTransport
  "How to invoke an actor and discover its capabilities."
  (invoke [transport conn actor opts]
    "Send `opts` (`{:task ... :room-id ... :from-actor ... :skill ...}`)
     to `actor`. Returns a result map:
       {:actor actor :status :dispatched :task <task-or-nil>}
       {:status :error :error <string>}
     `conn` is the Datahike connection in case the transport needs to
     persist a task ledger entry.")

  (probe-skills [transport conn actor]
    "Return the set of skill tags this actor can provide, as
     discovered from the underlying capability. Used to populate
     `:actor/skills` when an actor is first registered (e.g. when an
     MCP endpoint is wired in). Default impls return whatever's
     already in `(:actor/skills actor)`."))

;; ============================================================================
;; Registry
;; ============================================================================

(def ^:private transports (atom {}))

(defn register-transport!
  "Register a transport for an actor kind. Replaces any previous
   transport registered for that kind. Embedders (simmis, MCP hosts)
   call this once at startup."
  [kind transport]
  (swap! transports assoc kind transport)
  (tel/log! {:id :transport/registered :data {:kind kind}}
            "Actor transport registered")
  transport)

(defn get-transport
  "Look up the transport for an actor kind. Returns nil if nothing is
   registered (callers should surface a `:status :unsupported` result)."
  [kind]
  (get @transports kind))

(defn registered-kinds
  "Set of actor kinds that currently have a transport."
  []
  (set (keys @transports)))

;; ============================================================================
;; Built-in: :agent
;; ============================================================================

(defrecord AgentTransport []
  PActorTransport

  (invoke [_ _conn actor _opts]
    ;; Agents already react to messages posted into their rooms; the
    ;; harness does not auto-post a generic @-mention because the
    ;; caller usually wants control over the actual prompt. The
    ;; caller (often `var` from SCI) follows up with `room/post!` to
    ;; deliver the work. The dispatch result records that the actor
    ;; was selected.
    {:actor actor :status :dispatched :task nil})

  (probe-skills [_ _conn actor]
    (or (:skills actor) #{})))

;; ============================================================================
;; Built-in: :human
;; ============================================================================

(defn- post-mention-message!
  "Best-effort: post an @-mention into the target room. Failure is
   logged but never crashes the dispatch — the task ledger row is the
   load-bearing piece."
  [room-id actor body metadata]
  (require 'dvergr.discourse 'dvergr.room.registry)
  (let [room-lookup @(ns-resolve 'dvergr.room.registry 'lookup)
        room-post   @(ns-resolve 'dvergr.discourse 'post!)
        room-msg    @(ns-resolve 'dvergr.discourse 'message)]
    (try
      (when-let [room (room-lookup room-id)]
        (room-post room (room-msg {:from    :_system
                                   :to      (:id actor)
                                   :content body
                                   :metadata metadata})))
      (catch Throwable t
        (tel/log! {:level :warn :id :transport/room-post-failed
                   :data {:room-id room-id
                          :actor-id (:id actor)
                          :error (.getMessage t)}}
                  "Room post failed; caller may retry")))))

(defn- maybe-channel-notify!
  "If the human has channel external-refs (telegram, etc.) AND the
   relevant bridge is configured, fire a side notification. This is
   the boundary where simmis-style integrations enter — the bridge
   modules themselves are pluggable; today we hard-wire Telegram
   because it lives in this repo. Future: refactor to a peer-bus
   event that bridges subscribe to.

   Failures are logged but never bubble up; the in-room mention plus
   the task row is enough on its own."
  [actor task]
  (try
    (require 'dvergr.substrate.config 'dvergr.channels.telegram)
    (let [cfg-fn  @(ns-resolve 'dvergr.substrate.config 'config)
          tg-send @(ns-resolve 'dvergr.channels.telegram 'send-long-message!)
          chat-id (get-in actor [:external-refs :telegram])
          token   (some-> (cfg-fn) :telegram :bot-token)]
      (when (and chat-id token)
        (tg-send token (long chat-id)
                 (format "Task assigned: %s\n\n%s\n\n(task-id: %s)"
                         (or (some-> (:skill task) name) "(no skill)")
                         (:content task)
                         (:id task)))
        (tel/log! {:id :transport/telegram-sent
                   :data {:task-id (:id task) :chat-id chat-id}}
                  "Task notified via Telegram")))
    (catch Throwable t
      (tel/log! {:level :warn :id :transport/telegram-failed
                 :data {:task-id (:id task) :error (.getMessage t)}}))))

(defrecord HumanTransport []
  PActorTransport

  (invoke [_ conn actor {:keys [room-id from-actor skill task]
                         :or {room-id :boardroom}}]
    (let [t    (tasks/create-task! conn {:actor-id   (:id actor)
                                         :from-actor from-actor
                                         :room-id    room-id
                                         :skill      skill
                                         :content    task})
          body (format "@%s — task: %s\n\n%s\n\n(task-id: %s)"
                       (name (:id actor))
                       (or (some-> skill name) "(no skill)")
                       task
                       (:id t))]
      (post-mention-message! room-id actor body
                             {:task-id (:id t) :skill skill})
      (maybe-channel-notify! actor t)
      {:actor actor :status :dispatched :task t}))

  (probe-skills [_ _conn actor]
    ;; Humans declare their own skills — we can't probe them.
    (or (:skills actor) #{})))

;; ============================================================================
;; Bootstrap
;; ============================================================================

(defn install-defaults!
  "Register the built-in :agent and :human transports. Called once at
   namespace load. Embedders override later by calling
   `register-transport!` for the kind they want to replace."
  []
  (register-transport! :agent (->AgentTransport))
  (register-transport! :human (->HumanTransport)))

(install-defaults!)

;; ============================================================================
;; Top-level dispatch
;; ============================================================================

(defn dispatch
  "Invoke `actor` through the transport registered for its kind.
   Returns a uniform result map regardless of kind:

     {:actor actor :status :dispatched :task <t-or-nil>}
     {:status :error       :error <string>}
     {:status :unsupported :error <string> :actor actor}

   This is the layer `skills/dispatch!` delegates to once the target
   actor has been picked."
  [conn actor opts]
  (cond
    (nil? actor)
    {:status :no-provider}

    (nil? (:task opts))
    {:status :error :error ":task is required"}

    :else
    (if-let [transport (get-transport (:kind actor))]
      (try
        (invoke transport conn actor opts)
        (catch Throwable t
          (tel/log! {:level :error :id :transport/invoke-failed
                     :data {:actor-id (:id actor) :kind (:kind actor)
                            :error (.getMessage t)}})
          {:status :error :actor actor :error (.getMessage t)}))
      {:status :unsupported
       :actor actor
       :error (str "No transport registered for :kind " (:kind actor)
                   ". Embedders register one via "
                   "(dvergr.actors.transport/register-transport! "
                   (:kind actor) " <impl>).")})))
