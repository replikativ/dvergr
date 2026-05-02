(ns dvergr.rooms.bus
  "Room message pub/sub bus.

   When rooms/post-message! persists a message, it also publishes a lightweight
   event here. Agents subscribe to rooms by slug and receive events via mailbox.

   Architecture:
   - Per-slug subscriber map: {slug -> [mailbox ...]}
   - publish! routes events to all mailboxes subscribed to that slug
   - subscribe-room returns a mailbox (implements PAsyncSeq)
   - No global PAsyncSeq or pub/mult pump — simple, direct delivery

   Usage:
     (bus/init! execution-ctx)
     ;; In rooms/post-message!:
     (bus/publish! {:room-slug \"tg-123\" :preview \"Hello...\" ...})
     ;; In agent setup:
     (def src (bus/subscribe-room \"tg-123\"))
     ;; In agent loop:
     (let [[event rest] (await (anext src))] ...)"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :as sync]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private bus-state (atom nil))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize the room message bus.

   Must be called once at daemon startup with the execution context bound.

   Args:
     ctx - Spindel execution context"
  [ctx]
  (reset! bus-state {:subscribers (atom {})  ;; {slug -> [mailbox ...]}
                     :ctx         ctx})
  (tel/log! {:id :bus/initialized} "Room message bus initialized"))

(defn initialized?
  "Returns true if the bus has been initialized."
  []
  (some? @bus-state))

;; ============================================================================
;; Publishing
;; ============================================================================

(defn publish!
  "Publish a room message event to the bus.

   Called from rooms/post-message! after Datahike persistence.
   Routes the event to all mailboxes subscribed to the event's :room-slug.

   Event shape:
     {:room-slug  \"tg-123\"
      :room-id    <uuid>        ;; chat/id of the room
      :role       :user
      :source     \"Alice\"
      :preview    \"Hello...\"   ;; first 120 chars of content
      :timestamp  <inst>}"
  [event]
  (when-let [{:keys [subscribers ctx]} @bus-state]
    (let [slug (:room-slug event)
          mailboxes (get @subscribers slug)]
      (binding [rtc/*execution-context* ctx]
        (doseq [mbx mailboxes]
          (sync/post! mbx event))))))

;; ============================================================================
;; Subscription
;; ============================================================================

(defn subscribe-room
  "Subscribe to a room by slug. Returns a mailbox (implements PAsyncSeq).

   Multiple agents can subscribe to the same slug — each gets its own mailbox.

   Args:
     slug - Room slug string (e.g. \"tg-123456\")

   Returns mailbox or nil if bus not initialized."
  [slug]
  (when-let [{:keys [subscribers ctx]} @bus-state]
    (binding [rtc/*execution-context* ctx]
      (let [mbx (sync/mailbox)]
        (swap! subscribers update slug (fnil conj []) mbx)
        mbx))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn shutdown!
  "Shut down the bus. Clears state."
  []
  (reset! bus-state nil))
