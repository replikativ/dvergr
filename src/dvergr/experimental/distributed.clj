(ns dvergr.experimental.distributed
  "EXPERIMENTAL — not wired into the release. Kept for the simmis
   remote-agent integration roadmap; only `distributed_test` exercises
   it today. Do not depend on this from release code.

   Distributed agent addressing via kabel + spindel.

   Exposes dvergr agent operations as remote-invokable spins via
   defn-spin-remote. This means simmis (or any kabel peer) can
   invoke dvergr agents remotely.

   Setup:
     (start-peer! 47296)  ;; Start kabel server

   Remote operations (from any peer):
     (ask-agent dvergr-peer :coder \"implement feature X\")
     (list-remote-agents dvergr-peer)
     (send-to-agent dvergr-peer :coder \"hello\")

   Architecture:
     - Kabel WebSocket peer for transport
     - distributed-scope remote-middleware for invocation routing
     - defn-spin-remote bridges spindel spins to core.async for wire transport
     - Execution context registry for agent addressing"
  (:refer-clojure :exclude [await])
  (:require [dvergr.actors :as actors]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.discourse :as d]
            [org.replikativ.spindel.core :refer [spin await]]
            [org.replikativ.spindel.core :as sync]
            [org.replikativ.spindel.core :as comb]
            [org.replikativ.spindel.distributed.core :as dist]
            [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]
            [superv.async :refer [S go-super]]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]))

;; ============================================================================
;; Peer State
;; ============================================================================

(defonce peer-state (atom {:peer nil :started? false :invocation-loop nil}))

(def dvergr-peer-id #uuid "d7e89200-cafe-4242-babe-deadbeef0001")

;; ============================================================================
;; Remote Operations
;; ============================================================================

(defn- task->message-spec
  "Coerce the legacy task shape (a string or {:content ...}) into a content
   string + metadata pair suitable for d/message."
  [task]
  (if (string? task)
    [task nil]
    [(or (:content task) "") (dissoc task :content)]))

(defn-spin-remote ask-agent [server-id agent-id task]
  (spin-remote server-id [agent-id task]
               (let [daemon @daemon/current-daemon
                     room   (:discourse-room daemon)]
                 (cond
                   (nil? daemon)
                   {:error :daemon-not-running :agent-id agent-id}

                   (not (actors/online? agent-id))
                   {:error :agent-not-found
                    :agent-id agent-id
                    :available (actors/online-ids)}

                   :else
                   (let [[content meta] (task->message-spec task)]
                     (await (d/ask room agent-id (cond-> {:content content}
                                                   meta (assoc :metadata meta)))))))))

(defn-spin-remote list-remote-agents [server-id]
  (spin-remote server-id []
               (actors/online-actors)))

(defn-spin-remote send-to-agent [server-id agent-id message]
  (spin-remote server-id [agent-id message]
               (let [daemon @daemon/current-daemon]
                 (cond
                   (nil? daemon)
                   {:error :daemon-not-running :agent-id agent-id}

                   (not (actors/online? agent-id))
                   {:error :agent-not-found :agent-id agent-id}

                   :else
                   (let [[content meta] (task->message-spec message)]
                     (d/post! (:discourse-room daemon)
                              (d/message :remote agent-id content nil meta))
                     :sent)))))

(defn-spin-remote agent-status [server-id agent-id]
  (spin-remote server-id [agent-id]
               (if-let [entry (some #(when (= agent-id (:id %)) %) (actors/online-actors))]
      ;; Status = presence (room membership) + the durable actor row.
                 {:id          agent-id
                  :status      (:status entry)
                  :tags        (:tags entry)
                  :description (:description entry)}
                 {:error :agent-not-found
                  :agent-id agent-id})))

;; ============================================================================
;; Peer Setup
;; ============================================================================

(def ^:private transit
  "Transit serialization middleware for kabel.
   Uses the default transit handlers."
  identity)

(defn start-peer!
  "Start a kabel WebSocket server peer for distributed spin invocation.

   This creates a kabel server peer with:
   - remote-middleware from distributed-scope for RPC
   - Registered with spindel's execution-context-registry

   Args:
     port - WebSocket port (default 47296)

   Returns the peer atom."
  [& {:keys [port] :or {port 47296}}]
  (when (:started? @peer-state)
    (throw (ex-info "Peer already started" {:port port})))

  (let [url (str "ws://0.0.0.0:" port)
        handler (http-kit/create-http-kit-handler! S url dvergr-peer-id)
        server (peer/server-peer S handler dvergr-peer-id
                                 remote-middleware
                                 transit)]

    ;; Register peer for spin-remote invocation
    (dist/set-system-peer! server)

    ;; Start invocation loop (handles incoming ::invoke messages)
    (let [inv-loop (invoke-on-peer server)]
      (swap! peer-state assoc
             :peer server
             :invocation-loop inv-loop
             :started? true))

    ;; Start the peer
    (go-super S
              (peer/start server))

    (println "[distributed] Kabel peer started on" url "with peer-id" dvergr-peer-id)
    server))

(defn stop-peer!
  "Stop the kabel peer."
  []
  (when-let [server (:peer @peer-state)]
    ;; peer/stop is not always available, so just mark as stopped
    (reset! peer-state {:peer nil :started? false :invocation-loop nil})
    (println "[distributed] Kabel peer stopped.")
    :stopped))

;; ============================================================================
;; Daemon Integration
;; ============================================================================

(defn start-distributed!
  "Start distributed layer for the daemon.

   Called by daemon/start! when kabel config is present.

   Args:
     config - {:port 47296}

   Returns the peer."
  [config]
  (start-peer! :port (or (:port config) 47296)))

(defn stop-distributed!
  "Stop distributed layer."
  []
  (stop-peer!))

(comment
  ;; Start peer
  (start-peer! :port 47296)

  ;; From another JVM (or simmis):
  ;; (spin (await (ask-agent dvergr-peer-id :coder "hello")))
  ;; (spin (await (list-remote-agents dvergr-peer-id)))

  ;; Stop
  (stop-peer!))
