(ns dvergr.adapters.core
  "Transport-agnostic medium adapter: bridges an external chat medium
   (Telegram, Slack, Discord, web, simmis, …) to discourse Rooms.

   A *medium* has **venues** (a chat-id — a DM or a group) and **remote
   users**. The adapter maps:

     venue (chat-id) → one discourse Room (the persisted transcript)
     remote user     → a durable actor + a Participant joined lazily on
                       their first message, whose `on-message` is the
                       egress (send back to the venue's chat-id)

   Inbound:  medium event → post into the venue's Room AS the user-actor,
             addressed to the room's routed agent.
   Outbound: an agent reply `:to` a user-actor routes through the Bus to
             that user's egress Participant → sent back to the venue.

   This is the Participant-on-Bus model end to end: no response-sinks, no
   `:_system` fan-out. A DM is just the single-remote-user case; a group
   has several user-participants sharing one venue chat-id, and an agent
   addressing one of them sends exactly once.

   ## Thin vs rich adapters (the same shape at different distances)

   The render half comes in flavors distinguished by whether a `:send-fn`
   is supplied:

     - **thin** (Telegram, Slack…): supply `:send-fn`. Each remote user gets
       an egress Participant whose on-message sends agent replies back out
       over the channel.
     - **rich** (TUI, web, simmis): omit `:send-fn`. No egress Participant —
       the frontend renders by OBSERVING room state (in-process: subscribe to
       the room bus; remote/simmis: replicate the per-room datahike via
       konserve-sync). Agent replies live in the room; the frontend shows
       them. Inbound is identical; only render differs.

   Transport specifics — polling, message normalization, and the actual
   send — are injected as fns (`:send-fn`, `:ensure-room`, `:ensure-actor`),
   so this namespace is medium-agnostic and unit-testable without any
   network or persistence. Concrete media (Telegram, …) supply those fns."
  (:require [dvergr.discourse :as d]
            [clojure.string :as str]
            [taoensso.telemere :as tel]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.engine.core :as rtc]))

(defn make-adapter
  "Build an adapter from injected transport fns.

   Required:
     :ctx           — execution context the rooms live on
     :ensure-room   — (fn [chat-id]) → live discourse Room for the venue
                      (find-or-create + register + join the routed agent)
     :ensure-actor  — (fn [user]) → actor-id keyword for a normalized remote
                      user (upsert the durable row, return its id)
   Optional:
     :send-fn       — (fn [chat-id text]) sends text out to a venue. Supply for
                      a THIN adapter (Telegram): each user gets an egress
                      Participant. OMIT for a RICH adapter (TUI/web/simmis):
                      no egress is joined; the frontend renders by observing
                      room state.
     :default-agent — keyword the inbound message is FORCED to address (a thin
                      adapter routing to a specific agent). Omit to address the
                      room's agent via `dvergr.discourse/room-target` (the
                      default — what rich adapters want).
     :speaker-name  — (fn [actor-id]) → display label for an outbound message's
                      sender. Through a single bot every actor's reply looks
                      identical, so the egress prefixes `[<name>] ` (multiple
                      agents in a group, or distinguishing the agent from the
                      human). Defaults to the actor-id's name."
  [{:keys [ctx send-fn ensure-room ensure-actor default-agent speaker-name]}]
  {:ctx           ctx
   :send-fn       send-fn
   :ensure-room   ensure-room
   :ensure-actor  ensure-actor
   :default-agent default-agent
   :speaker-name  (or speaker-name (fn [id] (some-> id name)))
   ;; #{[room-id actor-id]} — remote users already joined as egress
   ;; participants, so re-arrival doesn't re-join.
   :joined        (atom #{})
   ;; #{room-id} — rooms already being mirrored out to their venue, so a
   ;; re-entry doesn't start a second relay.
   :mirrored      (atom #{})
   ;; #{message-id} — messages this adapter INJECTED (the venue's own inbound).
   ;; The room mirror skips these so a user's message isn't echoed back to them.
   :injected      (atom #{})
   ;; Single-thread executor for outbound sends. The egress is the boundary to
   ;; BLOCKING external I/O (an HTTP call ~hundreds of ms); running it on the
   ;; engine/spin thread blocks reactive work, and an unhandled send failure
   ;; (e.g. a Telegram 429 during a burst) would kill the egress pump — which is
   ;; exactly the "egress silently stopped" failure. One ordered worker keeps
   ;; message order, isolates errors, and never touches the engine thread.
   :sender        (when send-fn
                    (java.util.concurrent.Executors/newSingleThreadExecutor
                     (reify java.util.concurrent.ThreadFactory
                       (newThread [_ r]
                         (doto (Thread. ^Runnable r "dvergr-egress-send")
                           (.setDaemon true))))))})

(defn egress-participant
  "A Participant standing in for a remote user in their venue's room. Its
   `on-message` is the outbound edge: any message routed to this user
   (an agent reply addressed `:to` them) is sent to the venue's chat-id.
   Emits no reply of its own."
  [adapter chat-id actor-id]
  (d/participant
   {:id  actor-id
    :ctx (:ctx adapter)
    :on-message
    (fn [_p msg]
      (let [text  (:content msg)
             ;; Prefix the speaker — every actor's reply leaves through the
             ;; same bot, so without this var's and a human's relayed
             ;; messages are indistinguishable on the far side.
            from  (or (:from msg) (:source-agent-id msg) (:source-user msg))
            namer (or (:speaker-name adapter) (fn [id] (some-> id name)))
            label (when from (namer from))
            out   (if (and label (string? text)) (str "[" label "] " text) text)]
        (when (and (string? text) (not (str/blank? text)))
           ;; The egress relays every message it receives. Log the relay so a gap
           ;; (a message in the room that never reached the channel) is visible.
          (tel/log! {:level :debug :id :egress/relay
                     :data {:chat-id chat-id :from from :chars (count out)}})
           ;; Hand the BLOCKING send to the adapter's ordered sender thread —
           ;; never on the engine/spin thread, isolated so a send failure (e.g.
           ;; a Telegram 429) can't kill this participant's pump.
          (if-let [^java.util.concurrent.ExecutorService ex (:sender adapter)]
            (.submit ex ^Runnable
                     (fn [] (try ((:send-fn adapter) chat-id out)
                                 (catch Throwable e
                                   (tel/log! {:level :error :id :egress/send-failed
                                              :data {:chat-id chat-id :from from :error (.getMessage e)}})))))
            (try ((:send-fn adapter) chat-id out)
                 (catch Throwable e
                   (tel/log! {:level :error :id :egress/send-failed
                              :data {:chat-id chat-id :from from :error (.getMessage e)}})))))
         ;; No reply of our own.
        (binding [rtc/*execution-context* (:ctx adapter)] (spin nil))))
    :factory (fn [_ctx] (egress-participant adapter chat-id actor-id))}))

(defn ensure-user-joined!
  "Lazily join the remote user's egress Participant into `room` (once per
   [room actor]). Returns the room."
  [adapter room chat-id actor-id]
  (let [k [(:id room) actor-id]]
    (when-not (contains? @(:joined adapter) k)
      (binding [rtc/*execution-context* (:ctx room)]
        (d/join room (egress-participant adapter chat-id actor-id)))
      (swap! (:joined adapter) conj k))
    room))

(defn mirror-room!
  "Start relaying the WHOLE room out to `chat-id` (once per room). The channel
   becomes a mirror of the room — it sends EVERY conversational message, not
   just those addressed `:to` a venue user. This is what makes a thin channel
   show exactly what a rich UI (TUI/web) shows: an agent's reply to *another*
   participant (e.g. a TUI user, or another agent) is `:to that-one`, which a
   `:to`-filtered egress never saw — the silent-drop bug. Messages the adapter
   itself injected (the venue's own inbound) are skipped so a user isn't echoed
   their own text. Idempotent per room."
  [adapter chat-id room]
  (when (and (:send-fn adapter)
             (not (contains? @(:mirrored adapter) (:id room))))
    (swap! (:mirrored adapter) conj (:id room))
    (let [namer (or (:speaker-name adapter) (fn [id] (some-> id name)))
          ^java.util.concurrent.ExecutorService ex (:sender adapter)]
      (d/on-each-message room
                         (fn [msg]
                           (let [text (:content msg)]
                             (when (and (string? text) (not (str/blank? text))
                                        (not (contains? @(:injected adapter) (:id msg))))
                               (let [from  (:from msg)
                                     label (when from (namer from))
                                     out   (if label (str "[" label "] " text) text)
                                     send  (fn [] (try ((:send-fn adapter) chat-id out)
                                                       (catch Throwable e
                                                         (tel/log! {:level :error :id :egress/send-failed
                                                                    :data {:chat-id chat-id :from from
                                                                           :error (.getMessage e)}}))))]
                                 (tel/log! {:level :debug :id :egress/relay
                                            :data {:chat-id chat-id :from from :chars (count out)}})
                                 (if ex (.submit ex ^Runnable send) (send))))))))))

(defn inbound!
  "Handle one normalized inbound medium event:
     {:chat-id <venue> :user <normalized-remote-user> :text <string>}

   1. upsert the remote user's actor (→ actor-id)
   2. ensure the venue's Room exists (+ routed agent joined)
   3. lazily join the user's egress Participant
   4. post the message into the room AS the user-actor, addressed to the
      routed agent — the agent's reply will route back through the egress.

   Returns the actor-id, or nil if the event was empty/unroutable."
  [adapter {:keys [chat-id user text]}]
  (when (and chat-id (string? text) (not (str/blank? text)))
    (let [actor-id (when-let [f (:ensure-actor adapter)] (f user))
          actor-id (or actor-id :external)
          room     ((:ensure-room adapter) chat-id)]
      ;; Mirror only for thin adapters (a :send-fn to send back out). Rich
      ;; adapters (no :send-fn) render by observing the room, so no mirror — the
      ;; frontend already shows the whole room. The mirror relays the ENTIRE room
      ;; (not a :to-filtered subset), so an agent reply addressed to another
      ;; participant still reaches this venue.
      (when (:send-fn adapter)
        (mirror-room! adapter chat-id room))
      (binding [rtc/*execution-context* (:ctx room)]
        ;; Address the room's agent via the canonical rule (a forced
        ;; :default-agent — e.g. a thin Telegram adapter routing to a specific
        ;; agent — still wins). Tag :role :user in metadata: a persistent room's
        ;; store records the role from metadata (a user-actor is a keyword
        ;; :from, which the store would otherwise heuristically mis-label
        ;; :assistant). Agent replies carry no role metadata and store :assistant.
        (let [m (d/message actor-id
                           (or (:default-agent adapter) (d/room-target room))
                           text nil {:role :user})]
          ;; Record so the mirror skips the venue's own inbound (no self-echo).
          (swap! (:injected adapter) conj (:id m))
          (d/post! room m)))
      actor-id)))
