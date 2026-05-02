(ns dvergr.channels.core
  "Channel framework for connecting external services to dvergr agents.

   A channel is a map (data + functions) that:
   1. Connects to an external service (Telegram, email, etc.)
   2. Declares capabilities (what it *can* do)
   3. Has permissions (what agents are *allowed* to do) - a subset of capabilities
   4. Registers tools in dvergr's tool registry when connected
   5. Bridges incoming messages to agent mailboxes

   Usage:
     (require '[dvergr.channels.core :as ch])
     (require '[dvergr.channels.telegram :as tg])

     ;; Create and connect a channel
     (def bot (ch/connect! (tg/make-telegram {:token \"...\"})))

     ;; Wire messages to a callback
     (ch/connect! (tg/make-telegram {:token \"...\"})
                  :on-message (fn [msg] (println msg)))

     ;; List channels
     (ch/list-channels)

     ;; Disconnect
     (ch/disconnect! :my-telegram)"
  (:require [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]))

;; ============================================================================
;; Channel Registry
;; ============================================================================

(defonce channels (atom {}))

;; ============================================================================
;; Core Functions
;; ============================================================================

(defn get-channel
  "Look up a channel by id."
  [id]
  (get @channels id))

(defn list-channels
  "Return all registered channels as a vector of maps with :id, :type, :connected?."
  []
  (mapv (fn [[id ch]]
          {:id id
           :type (:type ch)
           :connected? (boolean (:connected? @(:state ch)))
           :capabilities (:capabilities ch)
           :permissions (:permissions ch)})
        @channels))

(defn connected?
  "Check if a channel is connected. Accepts channel map or channel id."
  [ch-or-id]
  (let [ch (if (map? ch-or-id) ch-or-id (get-channel ch-or-id))]
    (boolean (:connected? @(:state ch)))))

(defn- wrap-handler
  "Wrap a channel tool handler with permission check and error handling.
   Returns a function suitable for dvergr.tools/register!."
  [channel capability handler]
  (fn [input ctx]
    (if-not (contains? (:permissions channel) capability)
      {:type :error
       :error (str "Permission denied: " capability " is not granted for channel " (:id channel))}
      (if-not (connected? channel)
        {:type :error
         :error (str "Channel " (:id channel) " is not connected")}
        (try
          (handler input ctx)
          (catch Exception e
            {:type :error
             :error (str "Channel tool error: " (.getMessage e))}))))))

(defn- register-channel-tools!
  "Register a channel's permitted tools in both dvergr.tools and MCP server."
  [channel]
  (let [tools-by-cap (into {} (map (juxt :capability identity) (:tools channel)))
        handlers (:handlers channel)]
    (doseq [cap (:permissions channel)]
      (when-let [tool-def (get tools-by-cap cap)]
        (when-let [handler (get handlers (:name tool-def))]
          ;; Register in dvergr.tools global registry
          (tools/register!
           {:name (:name tool-def)
            :description (:description tool-def)
            :parameters (:parameters tool-def)
            :execute (wrap-handler channel cap handler)})
          ;; Register in MCP server
          (mcp/register-tool!
           {:name (:name tool-def)
            :description (:description tool-def)
            :inputSchema (:parameters tool-def)}
           (fn [_context arguments]
             (let [result ((wrap-handler channel cap handler) arguments {})]
               {:content [{:type "text"
                           :text (or (:content result)
                                     (:error result)
                                     (pr-str result))}]
                :isError (= :error (:type result))}))))))))

(defn- unregister-channel-tools!
  "Unregister a channel's tools from both registries."
  [channel]
  (let [tools-by-cap (into {} (map (juxt :capability identity) (:tools channel)))]
    (doseq [cap (:permissions channel)]
      (when-let [tool-def (get tools-by-cap cap)]
        (swap! tools/registry dissoc (:name tool-def))
        (mcp/unregister-tool! (:name tool-def))))))

(defn connect!
  "Connect a channel: call its connect fn, register permitted tools.

   Options (as trailing keyword args):
     :on-message - callback for incoming messages (fn [normalized-msg] ...)

   Returns the channel map (with :state updated to connected)."
  [channel & {:keys [on-message]}]
  (when on-message
    (swap! (:state channel) assoc :on-message on-message))
  (let [channel ((:connect! channel) channel)]
    (register-channel-tools! channel)
    (swap! channels assoc (:id channel) channel)
    channel))

(defn disconnect!
  "Disconnect a channel by id or channel map.
   Unregisters tools and calls channel's disconnect fn."
  [ch-or-id]
  (let [id (if (map? ch-or-id) (:id ch-or-id) ch-or-id)
        channel (get-channel id)]
    (when channel
      (unregister-channel-tools! channel)
      (when-let [disconnect-fn (:disconnect! channel)]
        (disconnect-fn channel))
      (swap! (:state channel) assoc :connected? false :on-message nil)
      (swap! channels dissoc id)
      :disconnected)))

(defn make-channel
  "Create a channel from a type-specific constructor map.

   The constructor map must contain:
     :id           - unique identifier keyword
     :type         - channel type keyword (e.g., :telegram, :email)
     :capabilities - set of capability keywords
     :permissions  - set of allowed capability keywords (subset of capabilities)
     :tools        - vector of tool definitions, each with :capability key
     :handlers     - map of tool-name -> handler-fn
     :connect!     - (fn [channel] -> channel') that establishes connection
     :disconnect!  - (fn [channel] -> nil) that tears down

   Optional:
     :config       - connection config map
     :state        - initial state atom (default: (atom {:connected? false}))"
  [{:keys [id type capabilities permissions tools handlers
           connect! disconnect! config state]
    :as channel-spec}]
  {:pre [(keyword? id)
         (keyword? type)
         (set? capabilities)
         (set? permissions)
         (every? capabilities permissions) ;; permissions is subset of capabilities
         (vector? tools)
         (map? handlers)
         (fn? connect!)]}
  (assoc channel-spec
         :state (or state (atom {:connected? false}))))
