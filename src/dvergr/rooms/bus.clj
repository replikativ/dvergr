(ns dvergr.rooms.bus
  "Room message pub/sub bus.

   When rooms/post-message! persists a message, it also publishes a lightweight
   event here. Agents subscribe to rooms by slug and receive events via mailbox.

   Architecture:
   - Per-slug subscriber map stored on the current spindel execution-context
     under `[:dvergr/rooms-bus/subscribers slug]` → vector of mailboxes
   - publish! routes events to all mailboxes subscribed to that slug
   - subscribe-room returns a mailbox (implements PAsyncSeq)
   - No global PAsyncSeq or pub/mult pump — simple, direct delivery

   Fork semantics: the subscriber map sits on the ctx state; a fork inherits
   the parent's map via overlay read-through and copy-on-writes on
   modification, so a fork's publishes/subscribes stay isolated from the
   parent's just like other ctx-scoped state.

   Usage (caller is responsible for binding the daemon's ctx):
     (binding [ec/*execution-context* daemon-ctx]
       (bus/publish! {:room-slug \"tg-123\" :preview \"…\" …})
       (def src (bus/subscribe-room \"tg-123\")))
     ;; In agent loop:
     (let [[event rest] (await (anext src))] …)"
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.core :as sync]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State path
;; ============================================================================

(def ^:private subs-path [:dvergr/rooms-bus/subscribers])

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Backwards-compatible no-op. The bus no longer holds a captured ctx —
   callers bind their own `ec/*execution-context*` and the subscriber
   map lives on that ctx. Kept so existing daemon startup code still
   compiles."
  ([] (init! nil))
  ([_ctx]
   (tel/log! {:id :bus/initialized} "Room message bus ready (ctx-scoped)")
   nil))

(defn initialized?
  "True if a spindel execution-context is currently bound. The bus has
   no global state to initialize anymore; this check preserves the
   pre-startup gate semantics for callers like rooms/post-message!."
  []
  (try (some? (ec/current-execution-context))
       (catch Throwable _ false)))

;; ============================================================================
;; Publishing
;; ============================================================================

(defn publish!
  "Publish a room message event to the bus.

   Called from rooms/post-message! after Datahike persistence.
   Routes the event to all mailboxes subscribed to the event's :room-slug
   on the current execution context.

   Event shape:
     {:room-slug  \"tg-123\"
      :room-id    <uuid>
      :role       :user
      :source     \"Alice\"
      :preview    \"Hello...\"
      :timestamp  <inst>}

   No-op when no ctx is bound (pre-daemon-startup case)."
  [event]
  (when (initialized?)
    (let [slug (:room-slug event)
          mailboxes (get (ec/get-state subs-path) slug)]
      (doseq [mbx mailboxes]
        (sync/post! mbx event)))))

;; ============================================================================
;; Subscription
;; ============================================================================

(defn subscribe-room
  "Subscribe to a room by slug. Returns a mailbox (implements PAsyncSeq).

   Multiple agents can subscribe to the same slug — each gets its own mailbox.

   Returns the mailbox, or nil if no execution context is currently bound."
  [slug]
  (when (initialized?)
    (let [mbx (sync/mailbox)]
      (ec/swap-state! subs-path
                      (fn [m] (update (or m {}) slug (fnil conj []) mbx)))
      mbx)))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn shutdown!
  "Drop every room subscriber on the current execution-context."
  []
  (when (initialized?)
    (ec/swap-state! subs-path (constantly {})))
  nil)
